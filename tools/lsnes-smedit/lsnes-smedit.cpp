#include "lsnes.hpp"

#include "core/advdumper.hpp"
#include "core/controller.hpp"
#include "core/framerate.hpp"
#include "core/instance.hpp"
#include "core/keymapper.hpp"
#include "core/mainloop.hpp"
#include "core/messages.hpp"
#include "core/misc.hpp"
#include "core/moviefile-common.hpp"
#include "core/moviedata.hpp"
#include "core/random.hpp"
#include "core/rom.hpp"
#include "core/settings.hpp"
#include "core/window.hpp"
#include "library/crandom.hpp"
#include "lua/lua.hpp"

#include <iostream>
#include <map>
#include <string>
#include <vector>

namespace {

void print_usage(const char* executable)
{
    std::cerr
        << "Usage: " << executable
        << " --rom=<rom> [--lua=<script>] [movie.lsmv]\n"
        << "       " << executable
        << " --rom-type=<type> --rom-a=<rom> [--lua=<script>] [movie.lsmv]\n";
}

std::string movie_argument(const std::vector<std::string>& arguments)
{
    for(const auto& argument : arguments) {
        if(!argument.empty() && argument[0] != '-')
            return argument;
    }
    return "";
}

std::map<std::string, std::string> settings_from(const std::vector<std::string>& arguments)
{
    std::map<std::string, std::string> settings;
    for(const auto& argument : arguments) {
        regex_results match;
        if(match = regex("--setting-(.*)=(.*)", argument))
            settings[match[1]] = match[2];
    }
    return settings;
}

void register_lua_scripts(const std::vector<std::string>& arguments)
{
    for(const auto& argument : arguments) {
        regex_results match;
        if(match = regex("--lua=(.*)", argument))
            lsnes_instance.lua2->add_startup_script(match[1]);
    }
}

bool has_rom_argument(const std::vector<std::string>& arguments)
{
    for(const auto& argument : arguments) {
        if(argument.compare(0, 6, "--rom=") == 0 ||
           argument.compare(0, 11, "--rom-type=") == 0)
            return true;
    }
    return false;
}

} // namespace

int main(int argc, char** argv)
{
    std::vector<std::string> arguments;
    for(int i = 1; i < argc; ++i)
        arguments.push_back(argv[i]);

    const std::string movie_filename = movie_argument(arguments);
    if(movie_filename.empty() && !has_rom_argument(arguments)) {
        print_usage(argv[0]);
        return 2;
    }

    bool platform_initialized = false;
    bool lua_initialized = false;

    try {
        crandom::init();
        reached_main();
        set_random_seed();

        platform::init();
        platform_initialized = true;

        // The common-library dummy graphics driver deliberately sets this to
        // false. smedit drives pauses and frame advances from Lua, so restore
        // the normal main-loop semantics without installing a GUI driver.
        platform::pausing_allowed = true;

        init_lua(lsnes_instance);
        lua_initialized = true;
        lsnes_instance.mdumper->set_output(&messages.getstream());
        set_hasher_callback([](uint64_t, uint64_t) {});
        init_main_callbacks();

        messages << "lsnes-smedit rr" << lsnes_version
                 << " (headless bsnes v085 Compatibility)" << std::endl;

        std::map<std::string, std::string> settings = settings_from(arguments);
        loaded_rom rom;
        moviefile* movie = nullptr;

        rom = construct_rom(movie_filename, arguments);
        if(rom.isnull())
            throw std::runtime_error("No ROM was specified");

        if(movie_filename.empty()) {
            rom.load(settings, DEFAULT_RTC_SECOND, DEFAULT_RTC_SUBSECOND);
            movie = new moviefile(rom, settings, DEFAULT_RTC_SECOND, DEFAULT_RTC_SUBSECOND);
        } else {
            movie = new moviefile(movie_filename, rom.get_internal_rom_type());
            rom.set_internal_region(movie->gametype->get_region());
            rom.load(movie->settings, movie->movie_rtc_second, movie->movie_rtc_subsecond);
        }

        *lsnes_instance.rom = rom;
        lsnes_instance.framerate->set_nominal_framerate(rom.region_approx_framerate());
        // Pause at boot so Lua idle can take over frame advance. Unpaused
        // dummy-graphics runs never call on_idle, so the mailbox never becomes ready.
        movie->start_paused = true;
        register_lua_scripts(arguments);

        // main_loop takes ownership of the movie object through do_load_state.
        main_loop(rom, *movie, true);

        lsnes_instance.mlogic->release_memory();
        lsnes_instance.buttons->cleanup();
        cleanup_keymapper();
        quit_lua(lsnes_instance);
        lua_initialized = false;
        platform::quit();
        platform_initialized = false;
        return 0;
    } catch(const std::bad_alloc&) {
        std::cerr << "lsnes-smedit: out of memory" << std::endl;
    } catch(const std::exception& error) {
        std::cerr << "lsnes-smedit: " << error.what() << std::endl;
    }

    if(lua_initialized)
        quit_lua(lsnes_instance);
    if(platform_initialized)
        platform::quit();
    return 1;
}
