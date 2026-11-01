# Host (Linux) build of the runner, used for fast local tests and CI.
# The Android build (per ABI, with the NDK toolchain) is driven from Gradle
# (app/build.gradle.kts) and compiles exactly the same sources.
CC      ?= cc
OUT     ?= build/host
LUA_DIR := ../third_party/lua
SRC     := $(filter-out $(LUA_DIR)/lua.c $(LUA_DIR)/luac.c,$(wildcard $(LUA_DIR)/*.c)) \
           ../third_party/lfs/lfs.c ../third_party/lua-zlib/lua_zlib.c \
           src/luart.c src/luart_ext.c
CFLAGS  ?= -O2
CFLAGS  += -std=gnu99 -Wall -DLUA_USE_LINUX -I$(LUA_DIR) -I../third_party/lfs
# --export-dynamic: lets prebuilt native rocks (.so) resolve lua_* symbols from the executable.
LDFLAGS += -Wl,--export-dynamic
LIBS    := -lz -lm -ldl

all: $(OUT)/luart

$(OUT)/luart: $(SRC) $(wildcard $(LUA_DIR)/*.h)
	@mkdir -p $(OUT)
	$(CC) $(CFLAGS) $(SRC) -o $@ $(LDFLAGS) $(LIBS)

test: $(OUT)/luart
	python3 tests/run_tests.py $(OUT)/luart
	python3 tests/test_rocks.py $(OUT)/luart

clean:
	rm -rf build
.PHONY: all test clean
