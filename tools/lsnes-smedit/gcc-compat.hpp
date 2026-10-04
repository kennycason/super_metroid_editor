#pragma once

// lsnes rr2-beta25 predates current libstdc++ header hygiene. Keep the
// compatibility shim outside the pinned upstream submodule so its gitlink can
// stay byte-for-byte at the released tag.
#include <algorithm>
#include <cinttypes>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <list>
#include <map>
#include <memory>
#include <set>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#ifndef LUA_OK
#define LUA_OK 0
#endif
