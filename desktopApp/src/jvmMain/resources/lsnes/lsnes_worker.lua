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
local outbox_dir = worker_dir .. "/outbox"
local reply_path = outbox_dir .. "/reply.json"
local wram_path = outbox_dir .. "/wram.bin"
local png_path = outbox_dir .. "/frame.png"
local done_path = outbox_dir .. "/done"

local pending_action = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}
local live_input = false
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
  elseif cmd.cmd == "save_state" then
    live_input = false
    exec("save-state " .. cmd.path)
    complete_after_frame(cmd)
  elseif cmd.cmd == "load_state" then
    live_input = false
    exec("load-smart " .. cmd.path)
    complete_after_frame(cmd)
  elseif cmd.cmd == "step" then
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
