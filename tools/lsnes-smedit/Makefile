# Popup-free smedit worker: common libraries + bsnes v085 Compatibility.
# Platform/wxwidgets is NOT part of this target. Dummy graphics come from
# core/dummygraphics.cpp via __all_common__.files. Never recurse into
# $(LSNES_DIR)/src/platform.

LSNES_DIR := $(abspath ../lsnes)
OPTIONS := $(abspath options.build)
OUTPUT_DIR ?= $(abspath bin)

include $(OPTIONS)

REALCC = $(CROSS_PREFIX)$(CC)
REALRANLIB = $(CROSS_PREFIX)$(RANLIB)
HOSTCCFLAGS = -std=gnu++14 $(USER_HOSTCCFLAGS) -O0
CFLAGS += -std=gnu++14 -pthread $(USER_CFLAGS)
LDFLAGS = -pthread -lboost_iostreams -lboost_filesystem -lz $(USER_LDFLAGS)
HOSTHELPER_LDFLAGS = $(USER_LDFLAGS)

ifeq ($(THREADS),NATIVE)
CFLAGS += -DNATIVE_THREADS
else
CFLAGS += -DBOOST_THREADS
LDFLAGS += -lboost_thread
endif

ifeq ($(REGEX),BOOST)
CFLAGS += -DUSE_BOOST_REGEX
LDFLAGS += -lboost_regex
HOSTHELPER_LDFLAGS += -lboost_regex
endif

ifdef NEED_LIBICONV
LDFLAGS += -liconv
endif

ifdef USE_LIBGCRYPT
CFLAGS += -DUSE_LIBGCRYPT_SHA256
LDFLAGS += -lgcrypt -lgpg-error
endif

ifdef USE_LIBLZMA
CFLAGS += -DLIBLZMA_AVAILABLE
LDFLAGS += -llzma
endif

ifndef NO_RDYNAMIC
LDFLAGS += -rdynamic
endif

ifdef NO_DLFCN
CFLAGS += -DNO_DLFCN
else
LDFLAGS += -ldl
endif

ifdef BOOST_FILESYSTEM3
CFLAGS += -DBOOST_FILESYSTEM3
endif

CFLAGS += $(shell $(CROSS_PREFIX)pkg-config $(LUA) --cflags)
LDFLAGS += $(shell $(CROSS_PREFIX)pkg-config $(LUA) --libs)
CFLAGS += $(shell $(CROSS_PREFIX)curl-config --cflags)
LDFLAGS += $(shell $(CROSS_PREFIX)curl-config --libs)

compiler = $(subst ++,cc,$(REALCC))
gambatte_compiler = $(REALCC)
export

EXE := $(OUTPUT_DIR)/smedit-lsnes-worker$(DOT_EXECUTABLE_SUFFIX)
OBJECT := $(OUTPUT_DIR)/lsnes-smedit.$(OBJECT_SUFFIX)
COMMON_FILES := $(LSNES_DIR)/src/__all_common__.files
COMMON_FLAGS := $(foreach library,core lua fonts library interface video emulation cmdhelp,$(LSNES_DIR)/src/$(library)/__all__.ldflags)

.PHONY: all prepare common clean

all: $(EXE)

prepare: | $(OUTPUT_DIR)
	$(HOSTCC) $(HOSTCCFLAGS) -o $(LSNES_DIR)/buildaux/version$(DOT_EXECUTABLE_SUFFIX) \
		$(LSNES_DIR)/buildaux/version.cpp
	$(HOSTCC) $(HOSTCCFLAGS) -o $(LSNES_DIR)/buildaux/mkdeps$(DOT_EXECUTABLE_SUFFIX) \
		$(LSNES_DIR)/buildaux/mkdeps.cpp -lboost_filesystem
	python3 -c "from pathlib import Path; t=Path(r'$(LSNES_DIR)/buildaux/txt2cstr.cpp').read_text(); old='have_quote = false;\n\t\t}\n\t}'; new='have_quote = false;\n\t\t}\n\t\treturn 0;\n\t}'; t2=t.replace(old,new,1); assert t2!=t, 'txt2cstr return patch failed'; Path(r'$(OUTPUT_DIR)/txt2cstr.cpp').write_text(t2)"
	$(HOSTCC) $(HOSTCCFLAGS) -o $(LSNES_DIR)/buildaux/txt2cstr$(DOT_EXECUTABLE_SUFFIX) \
		$(OUTPUT_DIR)/txt2cstr.cpp
	cd $(LSNES_DIR) && ./buildaux/version$(DOT_EXECUTABLE_SUFFIX) >src/core/version.cpp
	$(MAKE) -C $(LSNES_DIR)/src/cmdhelp prebuild
	$(MAKE) -C $(LSNES_DIR)/src/core precheck
	$(MAKE) -C $(LSNES_DIR)/src/emulation precheck
	$(MAKE) -C $(LSNES_DIR)/src/fonts precheck
	$(MAKE) -C $(LSNES_DIR)/src/interface precheck
	$(MAKE) -C $(LSNES_DIR)/src/library precheck
	$(MAKE) -C $(LSNES_DIR)/src/lua precheck
	$(MAKE) -C $(LSNES_DIR)/src/video precheck

common: prepare
	$(MAKE) -C $(LSNES_DIR)/src __all_common__.files

$(OUTPUT_DIR):
	mkdir -p $@

$(OBJECT): lsnes-smedit.cpp gcc-compat.hpp | $(OUTPUT_DIR)
	$(REALCC) $(CFLAGS) -I$(LSNES_DIR)/include -c -o $@ $<

$(EXE): common $(OBJECT)
	cd $(LSNES_DIR)/src && $(REALCC) -o $(EXE) $(OBJECT) `cat __all_common__.files` $(LDFLAGS) `cat $(foreach library,core lua fonts library interface video emulation cmdhelp,$(library)/__all__.ldflags)`

clean:
	rm -f $(OBJECT) $(EXE) $(OUTPUT_DIR)/txt2cstr.cpp
	rm -f $(LSNES_DIR)/buildaux/version$(DOT_EXECUTABLE_SUFFIX)
	rm -f $(LSNES_DIR)/buildaux/mkdeps$(DOT_EXECUTABLE_SUFFIX)
	rm -f $(LSNES_DIR)/buildaux/txt2cstr$(DOT_EXECUTABLE_SUFFIX)
	$(MAKE) -C $(LSNES_DIR)/src/core clean
	$(MAKE) -C $(LSNES_DIR)/src/emulation clean
	$(MAKE) -C $(LSNES_DIR)/src/fonts clean
	$(MAKE) -C $(LSNES_DIR)/src/interface clean
	$(MAKE) -C $(LSNES_DIR)/src/library clean
	$(MAKE) -C $(LSNES_DIR)/src/lua clean
	$(MAKE) -C $(LSNES_DIR)/src/video clean
	$(MAKE) -C $(LSNES_DIR)/src/cmdhelp clean
	rm -f $(LSNES_DIR)/src/__all_common__.files
	rm -f $(LSNES_DIR)/src/__all_platform__.files
