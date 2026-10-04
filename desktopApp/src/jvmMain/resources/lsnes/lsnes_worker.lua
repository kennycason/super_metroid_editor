-- SMEDIT adapter for the popup-free lsnes rr2-beta25 worker.
--
-- File-mailbox protocol adapted from sm_ceres. The worker owns the emulator
-- callbacks and deliberately uses callback.register so user Lua scripts can
-- run beside it. SNES-12 order: B Y select start up down left right A X L R.

local WRAM = 0x7E0000
local WRAM_SIZE = 0x2000
local ADDR_POSE = 0x0A1C
local JOY_NAMES = {"B", "Y", "select", "start", "up", "down", "left", "right", "A", "X", "L", "R"}

local worker_dir = os.getenv("LSNES_WORKER_DIR")
if not worker_dir or worker_dir == "" then
  error("LSNES_WORKER_DIR is required")
end

local inbox_path = worker_dir .. "/inbox.json"
local buttons_path = worker_dir .. "/buttons.json"
local outbox_dir = worker_dir .. "/outbox"
local reply_path = outbox_dir .. "/reply.json"
local wram_path = outbox_dir .. "/wram.bin"
local png_path = outbox_dir .. "/frame.png"
local done_path = outbox_dir .. "/done"
local live_json_path = outbox_dir .. "/live.json"
local live_png_path = outbox_dir .. "/live_frame.png"
local live_wram_path = outbox_dir .. "/live_wram.bin"

-- NTSC SNES: 60.0988 Hz. Dummy graphics skip lsnes's own wait, so we pace here.
local FRAME_USEC = 16639
local MAX_LIVE_SPEED = 16

local pending_action = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}
local live_input = false
local live_mode = false
local live_speed = 1
local live_wram_counter = 0
local next_frame_deadline = nil
local seek_until = nil
local seek_cmd = nil
local seek_was_live = false
local current_cmd = nil
local target_frame = nil
local last_emulated_frame = nil
local finished = false
local initialized = false

local function log(msg)
  print("[smedit-worker] " .. tostring(msg))
end

local function read_file(path)
  local file = io.open(path, "r")
  if not file then return nil end
  local text = file:read("*a")
  file:close()
  return text
end

local function write_file(path, text, binary)
  local file = io.open(path, binary and "wb" or "w")
  if not file then return false end
  file:write(text)
  file:close()
  return true
end

local function json_unescape(value)
  return (value:gsub('\\"', '"'):gsub("\\\\", "\\"))
end

local function json_escape(value)
  return tostring(value or ""):gsub("\\", "\\\\"):gsub('"', '\\"'):gsub("\n", "\\n")
end

local function parse_number_array(text, field)
  local values = {}
  local body = text:match('"' .. field .. '"%s*:%s*%[([^%]]*)%]')
  if body then
    for number in body:gmatch("%-?%d+") do
      values[#values + 1] = tonumber(number) or 0
    end
  end
  return values
end

local function parse_cmd(text)
  if not text then return nil end
  local cmd = {
    id = text:match('"id"%s*:%s*"([^"]*)"') or "",
    cmd = text:match('"cmd"%s*:%s*"([^"]*)"') or "",
    path = text:match('"path"%s*:%s*"([^"]*)"'),
    address = tonumber(text:match('"address"%s*:%s*(%-?%d+)')),
    includeFrame = not text:match('"includeFrame"%s*:%s*false'),
    includeWram = not text:match('"includeWram"%s*:%s*false'),
    applyButtons = not text:match('"applyButtons"%s*:%s*false'),
    repeatFrames = tonumber(text:match('"repeat"%s*:%s*(%d+)')) or 1,
    targetFrame = tonumber(text:match('"targetFrame"%s*:%s*(%d+)')),
    action = parse_number_array(text, "action"),
    data = parse_number_array(text, "data"),
  }
  if cmd.path then cmd.path = json_unescape(cmd.path) end
  while #cmd.action < 12 do cmd.action[#cmd.action + 1] = 0 end
  return cmd
end

local function frame_now()
  if movie and movie.currentframe then return movie.currentframe() end
  return 0
end

local function reply_frame()
  return last_emulated_frame or frame_now()
end

local function set_paused(paused)
  local mode = gui and gui.get_runmode and gui.get_runmode() or "unknown"
  local is_paused = mode == "pause" or mode == "pause_break"
  if paused and not is_paused then
    exec("pause-emulator")
  elseif not paused and is_paused then
    exec("unpause-emulator")
  end
end

local function dump_wram()
  local chunks = {}
  local buffer = {}
  for address = 0, WRAM_SIZE - 1 do
    buffer[#buffer + 1] = string.char(memory.readbyte(WRAM + address) % 256)
    if #buffer == 1024 then
      chunks[#chunks + 1] = table.concat(buffer)
      buffer = {}
    end
  end
  if #buffer > 0 then chunks[#chunks + 1] = table.concat(buffer) end
  return table.concat(chunks)
end

local function action_json(action)
  local values = {}
  for index = 1, 12 do values[index] = tostring(action[index] or 0) end
  return "[" .. table.concat(values, ",") .. "]"
end

local function write_error(cmd, err)
  write_file(
    reply_path,
    string.format('{"id":"%s","ok":false,"error":"%s","frame":%d}\n',
      json_escape(cmd and cmd.id or ""), json_escape(err), reply_frame())
  )
  write_file(done_path, "error\n")
end

local function write_reply(cmd)
  local ok, err = pcall(function()
    local wram = nil
    if cmd.includeWram ~= false then
      wram = dump_wram()
      assert(write_file(wram_path, wram, true), "cannot write WRAM")
    end
    if cmd.includeFrame ~= false and gui and gui.screenshot then
      pcall(gui.screenshot, png_path)
    end
    local pose = memory.readword(WRAM + ADDR_POSE)
    local header = string.format(
      '{"id":"%s","ok":true,"frame":%d,"version":"lsnes-rr2-beta25","core":"lsnes-bsnes-v085","width":256,"height":224,"action":%s,"pose":%d,"wramBinaryLength":%d}\n',
      json_escape(cmd.id), reply_frame(), action_json(cmd.action or pending_action), pose, wram and #wram or 0
    )
    assert(write_file(reply_path, header), "cannot write reply")
    assert(write_file(done_path, "ok\n"), "cannot write completion marker")
  end)
  if not ok then write_error(cmd, err) end
end

local function apply_joyset(action)
  local controls = {}
  for index = 1, 12 do controls[JOY_NAMES[index]] = (action[index] or 0) ~= 0 end
  input.joyset(1, controls)
end

local function now_usec()
  if not utime then return 0 end
  local seconds, microseconds = utime()
  return (seconds or 0) * 1000000 + (microseconds or 0)
end

local function atomic_write(path, text, binary)
  local tmp = path .. ".tmp"
  if not write_file(tmp, text, binary) then return false end
  os.remove(path)
  return os.rename(tmp, path) and true or false
end

local function poll_buttons()
  local text = read_file(buttons_path)
  if not text then return end
  local action = parse_number_array(text, "action")
  if #action >= 12 then
    pending_action = action
    while #pending_action < 12 do pending_action[#pending_action + 1] = 0 end
  end
  live_input = not text:match('"apply"%s*:%s*false')
  local speed = tonumber(text:match('"speed"%s*:%s*(%d+)')) or 1
  if speed < 1 then speed = 1 end
  if speed > MAX_LIVE_SPEED then speed = MAX_LIVE_SPEED end
  live_speed = speed
end

local function publish_live(include_png, include_wram)
  if include_wram then
    local wram = dump_wram()
    atomic_write(live_wram_path, wram, true)
  end
  if include_png and gui and gui.screenshot then
    local tmp = live_png_path .. ".tmp"
    os.remove(tmp)
    pcall(gui.screenshot, tmp)
    os.remove(live_png_path)
    os.rename(tmp, live_png_path)
  end
  local pose = memory.readword(WRAM + ADDR_POSE)
  atomic_write(
    live_json_path,
    string.format('{"frame":%d,"pose":%d,"width":256,"height":224}\n', reply_frame(), pose)
  )
end

local function frame_period()
  local speed = live_speed or 1
  if speed < 1 then speed = 1 end
  if speed > MAX_LIVE_SPEED then speed = MAX_LIVE_SPEED end
  return FRAME_USEC / speed
end

local function pace_live()
  local period = frame_period()
  local now = now_usec()
  if not next_frame_deadline then
    next_frame_deadline = now + period
    return
  end
  if now < next_frame_deadline then
    while now_usec() < next_frame_deadline do end
  else
    local missed = math.floor((now - next_frame_deadline) / period)
    if missed > 3 then
      next_frame_deadline = now
    end
  end
  next_frame_deadline = next_frame_deadline + period
end

local function complete_after_frame(cmd, frame_count)
  current_cmd = cmd
  local base_frame = last_emulated_frame or frame_now()
  target_frame = frame_count and (base_frame + math.max(1, math.floor(frame_count))) or nil
  -- Unpause once; frame_emulated re-pauses after the requested batch.
  set_paused(false)
end

local function handle_cmd(cmd)
  if not cmd or cmd.cmd == "" then return end
  if cmd.cmd == "quit" then
    finished = true
    write_reply(cmd)
    exec("quit-emulator")
  elseif cmd.cmd == "hello" or cmd.cmd == "snapshot" then
    write_reply(cmd)
  elseif cmd.cmd == "load_script" then
    local ok, err = pcall(dofile, cmd.path)
    if ok then write_reply(cmd) else write_error(cmd, err) end
  elseif cmd.cmd == "write_memory" then
    local ok, err = pcall(function()
      assert(cmd.address and cmd.address >= 0, "invalid WRAM address")
      assert(cmd.address + #cmd.data <= WRAM_SIZE, "WRAM write out of bounds")
      for index, value in ipairs(cmd.data) do
        memory.writebyte(WRAM + cmd.address + index - 1, value % 256)
      end
    end)
    if ok then write_reply(cmd) else write_error(cmd, err) end
  elseif cmd.cmd == "run" then
    live_mode = true
    live_input = cmd.applyButtons
    pending_action = cmd.action
    live_wram_counter = 0
    live_speed = 1
    next_frame_deadline = nil
    set_paused(false)
    write_reply(cmd)
  elseif cmd.cmd == "seek" then
    if not cmd.targetFrame then
      write_error(cmd, "seek requires targetFrame")
      return
    end
    seek_until = cmd.targetFrame
    seek_cmd = cmd
    seek_was_live = live_mode
    live_mode = true
    next_frame_deadline = nil
    set_paused(false)
  elseif cmd.cmd == "pause" then
    live_mode = false
    set_paused(true)
    write_reply(cmd)
  elseif cmd.cmd == "save_state" then
    if not live_mode then live_input = false end
    exec("save-state " .. cmd.path)
    if live_mode then write_reply(cmd) else complete_after_frame(cmd) end
  elseif cmd.cmd == "load_state" then
    if not live_mode then live_input = false end
    exec("load-smart " .. cmd.path)
    if live_mode then write_reply(cmd) else complete_after_frame(cmd) end
  elseif cmd.cmd == "step" then
    live_mode = false
    pending_action = cmd.action
    live_input = cmd.applyButtons
    complete_after_frame(cmd, cmd.repeatFrames)
  else
    write_error(cmd, "unknown command: " .. tostring(cmd.cmd))
  end
end

local function poll_inbox()
  if finished then return end
  local text = read_file(inbox_path)
  if not text then return end
  os.remove(inbox_path)
  handle_cmd(parse_cmd(text))
end

local function on_worker_input()
  if live_input then apply_joyset(pending_action) end
end

local function on_worker_frame()
  last_emulated_frame = frame_now()
  if seek_until then
    poll_inbox()
    if last_emulated_frame >= seek_until then
      local cmd = seek_cmd
      seek_until = nil
      seek_cmd = nil
      next_frame_deadline = nil
      if not seek_was_live then
        live_mode = false
        set_paused(true)
      end
      publish_live(true, true)
      if cmd then write_reply(cmd) end
    end
    return
  end
  if live_mode then
    live_wram_counter = live_wram_counter + 1
    -- Inbox/buttons every frame at 1x; every 4th frame at 4x+ so pause/speed
    -- still land within ~10ms. Failed inbox opens were a measurable cut.
    if live_speed < 4 or live_wram_counter % 4 == 1 then
      poll_inbox()
      poll_buttons()
      if not live_mode then return end
    end
    local include_png, include_wram, include_json
    if live_speed >= 4 then
      -- PNG ~60 UI fps. live.json every 8 emulated frames so Kotlin FPS
      -- still tracks, without a tmp+rename per core frame.
      include_png = live_wram_counter % live_speed == 0
      include_wram = live_wram_counter % (live_speed * 10) == 0
      include_json = include_png or live_wram_counter % 8 == 0
    else
      local now = now_usec()
      local remaining = next_frame_deadline and (next_frame_deadline - now) or frame_period()
      include_png = remaining > 4000
      include_wram = live_wram_counter % 10 == 0
      include_json = true
    end
    if include_json then
      publish_live(include_png, include_wram)
    end
    -- 8x/16x already run slower than FRAME_USEC/speed on this core (~430 fps).
    if live_speed < 8 then
      pace_live()
    end
    return
  end
  if current_cmd then
    if target_frame and last_emulated_frame < target_frame then return end
    local cmd = current_cmd
    current_cmd = nil
    target_frame = nil
    set_paused(true)
    write_reply(cmd)
  end
end

local function on_worker_idle()
  if finished then return end
  if not initialized then
    initialized = true
    exec("enable-sound off")
    set_paused(true)
    exec("clear-pause-on-end")
    write_file(done_path, "ready\n")
    log("ready: lsnes rr2-beta25 headless")
  end
  poll_inbox()
  set_idle_timeout(20000)
end

if callback and callback.register then
  callback.register("input", on_worker_input)
  -- Only frame_emulated: on_frame fires for the current frame as soon as
  -- +advance-frame unpauses, which would ACK the step before it advances.
  callback.register("frame_emulated", on_worker_frame)
  callback.register("idle", on_worker_idle)
else
  function on_input() on_worker_input() end
  function on_frame_emulated() on_worker_frame() end
  function on_idle() on_worker_idle() end
end

set_idle_timeout(200000)
log("starting: lsnes rr2-beta25 headless")
