/*
** luart - Lua 5.4 runner for the "Lua Android" host application.
**
** One OS process per job. The host (Kotlin) can therefore enforce hard
** deadlines and cancellation with SIGKILL, and a crashing script or native
** module can never take the host down.
**
** Modes
**   luart exec  --runtime DIR --job DIR [limits]   sandboxed job execution
**   luart rocks --runtime DIR [limits] -- ARGS...   LuaRocks (sources in <runtime>/luarocks)
**   luart version                                   JSON runtime description
**
** Limits: --timeout-ms N  --mem-bytes N  --fsize-bytes N  --out-bytes N
**         --result-bytes N
**
** The Lua side of every mode lives in <runtime>/{bootstrap,rocks_main}.lua.
*/
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#include "lauxlib.h"
#include "lua.h"
#include "lualib.h"

#define LUART_VERSION "1.0"

int luaopen_lfs(lua_State *L);
int luaopen_zlib(lua_State *L);
int luaopen_md5(lua_State *L);
int luaopen_posix(lua_State *L);

/* ------------------------------------------------------------------ */
/* Memory accounting allocator                                         */
/* ------------------------------------------------------------------ */

typedef struct {
  size_t used, limit, peak;
  int exceeded;
} MemState;

static MemState g_mem;

static void *l_alloc(void *ud, void *ptr, size_t osize, size_t nsize) {
  MemState *m = (MemState *)ud;
  if (nsize == 0) {
    if (ptr) m->used -= osize;
    free(ptr);
    return NULL;
  }
  size_t old = ptr ? osize : 0;
  if (nsize > old && m->limit != 0) {
    size_t grow = nsize - old;
    if (grow > m->limit || m->used > m->limit - grow) {
      m->exceeded = 1;
      return NULL;
    }
  }
  void *np = realloc(ptr, nsize);
  if (np) {
    m->used = m->used - old + nsize;
    if (m->used > m->peak) m->peak = m->used;
  }
  return np;
}

/* ------------------------------------------------------------------ */
/* Deadline and cancellation                                           */
/* ------------------------------------------------------------------ */

static volatile sig_atomic_t g_timed_out = 0;
static volatile sig_atomic_t g_hook_fired = 0;
static char g_status_path[PATH_MAX];

static const char JSON_TIMEOUT[] =
    "{\"status\":\"timeout\",\"errorCode\":\"EXECUTION_TIMEOUT\","
    "\"errorMessage\":\"Execution deadline exceeded\"}\n";
static const char JSON_CANCEL[] =
    "{\"status\":\"cancelled\",\"errorCode\":\"EXECUTION_CANCELLED\","
    "\"errorMessage\":\"Execution cancelled\"}\n";

/* Only async-signal-safe calls in here. */
static void write_status_raw(const char *json, size_t len) {
  if (g_status_path[0] == '\0') return;
  int fd = open(g_status_path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
  if (fd < 0) return;
  ssize_t r = write(fd, json, len);
  (void)r;
  close(fd);
}

static void on_alarm(int sig) {
  (void)sig;
  if (!g_timed_out) {
    /* Stage 1: ask the VM to unwind gracefully (flushes output, writes the
       normal status file). Stage 2 below is the backstop for scripts stuck in
       a C function or swallowing the error with pcall. */
    g_timed_out = 1;
    alarm(1);
    return;
  }
  write_status_raw(JSON_TIMEOUT, sizeof(JSON_TIMEOUT) - 1);
  _exit(124);
}

static void on_term(int sig) {
  (void)sig;
  write_status_raw(JSON_CANCEL, sizeof(JSON_CANCEL) - 1);
  _exit(143);
}

static void count_hook(lua_State *L, lua_Debug *ar) {
  (void)ar;
  if (g_timed_out && !g_hook_fired) {
    g_hook_fired = 1;
    luaL_error(L, "execution deadline exceeded");
  }
}

/* ------------------------------------------------------------------ */
/* luart.* helpers exposed to the (trusted) Lua bootstrap               */
/* ------------------------------------------------------------------ */

static const char *abi_name(void) {
#if defined(__aarch64__)
  return "arm64-v8a";
#elif defined(__arm__)
  return "armeabi-v7a";
#elif defined(__x86_64__)
  return "x86_64";
#elif defined(__i386__)
  return "x86";
#else
  return "unknown";
#endif
}

static int l_now_ms(lua_State *L) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  lua_pushinteger(L, (lua_Integer)ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
  return 1;
}

static int l_sleep_ms(lua_State *L) {
  lua_Integer ms = luaL_checkinteger(L, 1);
  if (ms < 0) ms = 0;
  struct timespec ts = {(time_t)(ms / 1000), (long)(ms % 1000) * 1000000L};
  while (nanosleep(&ts, &ts) == -1 && errno == EINTR) {
  }
  return 0;
}

static int l_exit(lua_State *L) {
  int code = (int)luaL_checkinteger(L, 1);
  fflush(NULL);
  _exit(code);
  return 0;
}

static int l_timed_out(lua_State *L) {
  lua_pushboolean(L, g_timed_out != 0);
  return 1;
}

static int l_mem(lua_State *L) {
  lua_pushinteger(L, (lua_Integer)g_mem.used);
  lua_pushinteger(L, (lua_Integer)g_mem.peak);
  lua_pushboolean(L, g_mem.exceeded != 0);
  return 3;
}

/* Give the bootstrap headroom to write its status after an OOM. */
static int l_relax_mem(lua_State *L) {
  lua_Integer extra = luaL_optinteger(L, 1, 8 * 1024 * 1024);
  if (g_mem.limit != 0) g_mem.limit += (size_t)extra;
  return 0;
}

static int l_getpid(lua_State *L) {
  lua_pushinteger(L, (lua_Integer)getpid());
  return 1;
}

static int l_setenv(lua_State *L) {
  const char *k = luaL_checkstring(L, 1);
  const char *v = luaL_checkstring(L, 2);
  lua_pushboolean(L, setenv(k, v, 1) == 0);
  return 1;
}

static const luaL_Reg luart_funcs[] = {{"now_ms", l_now_ms},   {"sleep_ms", l_sleep_ms},
                                       {"exit", l_exit},       {"timed_out", l_timed_out},
                                       {"mem", l_mem},         {"relax_mem", l_relax_mem},
                                       {"getpid", l_getpid},   {"setenv", l_setenv},
                                       {NULL, NULL}};

static void push_luart_table(lua_State *L) {
  luaL_newlib(L, luart_funcs);
  lua_pushstring(L, LUART_VERSION);
  lua_setfield(L, -2, "version");
  lua_pushstring(L, abi_name());
  lua_setfield(L, -2, "abi");
  lua_pushstring(L, LUA_RELEASE);
  lua_setfield(L, -2, "lua");
  lua_pushinteger(L, (lua_Integer)sizeof(void *) * 8);
  lua_setfield(L, -2, "pointer_bits");
}

/* ------------------------------------------------------------------ */
/* Options                                                             */
/* ------------------------------------------------------------------ */

typedef struct {
  const char *mode, *runtime, *job, *rocks, *luarocks;
  long long timeout_ms, mem_bytes, fsize_bytes, out_bytes, result_bytes;
  int first_extra;
} Opts;

static void die_usage(const char *msg) {
  fprintf(stderr, "luart: %s\n", msg);
  fprintf(stderr,
          "usage: luart exec --runtime DIR --job DIR [--rocks DIR] [--timeout-ms N] "
          "[--mem-bytes N] [--fsize-bytes N] [--out-bytes N] [--result-bytes N]\n"
          "       luart rocks --runtime DIR [limits] -- LUAROCKS-ARGS...   (env: LUART_TREE, LUART_CONF_DIR, LUART_SERVERS)\n"
          "       luart version\n");
  exit(64);
}

static long long parse_ll(const char *opt, const char *s) {
  char *end = NULL;
  errno = 0;
  long long v = strtoll(s, &end, 10);
  if (errno != 0 || end == s || *end != '\0' || v < 0) {
    fprintf(stderr, "luart: invalid value for %s: %s\n", opt, s);
    exit(64);
  }
  return v;
}

static void parse_args(int argc, char **argv, Opts *o) {
  memset(o, 0, sizeof *o);
  o->mem_bytes = 128LL << 20;
  o->fsize_bytes = 64LL << 20;
  o->out_bytes = 1LL << 20;
  o->result_bytes = 1LL << 20;
  o->first_extra = argc;
  if (argc < 2) die_usage("missing mode");
  o->mode = argv[1];
  for (int i = 2; i < argc; i++) {
    const char *a = argv[i];
    if (strcmp(a, "--") == 0) {
      o->first_extra = i + 1;
      break;
    }
    if (i + 1 >= argc) die_usage("option requires a value");
    const char *v = argv[++i];
    if (!strcmp(a, "--runtime")) o->runtime = v;
    else if (!strcmp(a, "--job")) o->job = v;
    else if (!strcmp(a, "--rocks")) o->rocks = v;
    else if (!strcmp(a, "--luarocks")) o->luarocks = v;
    else if (!strcmp(a, "--timeout-ms")) o->timeout_ms = parse_ll(a, v);
    else if (!strcmp(a, "--mem-bytes")) o->mem_bytes = parse_ll(a, v);
    else if (!strcmp(a, "--fsize-bytes")) o->fsize_bytes = parse_ll(a, v);
    else if (!strcmp(a, "--out-bytes")) o->out_bytes = parse_ll(a, v);
    else if (!strcmp(a, "--result-bytes")) o->result_bytes = parse_ll(a, v);
    else die_usage("unknown option");
  }
}

/* ------------------------------------------------------------------ */
/* Helpers                                                             */
/* ------------------------------------------------------------------ */

static char *read_file(const char *path, size_t *len) {
  FILE *f = fopen(path, "rb");
  if (!f) return NULL;
  size_t cap = 16384, n = 0;
  char *buf = (char *)malloc(cap);
  if (!buf) {
    fclose(f);
    return NULL;
  }
  size_t r;
  while ((r = fread(buf + n, 1, cap - n, f)) > 0) {
    n += r;
    if (n == cap) {
      cap *= 2;
      char *nb = (char *)realloc(buf, cap);
      if (!nb) {
        free(buf);
        fclose(f);
        return NULL;
      }
      buf = nb;
    }
  }
  fclose(f);
  *len = n;
  return buf;
}

/* Minimal JSON string escaping for the C-level fallback status file. */
static void write_internal_error(const char *msg) {
  char out[1400];
  size_t o = 0;
  const char *head = "{\"status\":\"error\",\"errorCode\":\"INTERNAL_ERROR\",\"errorMessage\":\"";
  o += (size_t)snprintf(out, sizeof out, "%s", head);
  for (const char *p = msg; *p && o < sizeof out - 16; p++) {
    unsigned char c = (unsigned char)*p;
    if (c == '"' || c == '\\') {
      out[o++] = '\\';
      out[o++] = (char)c;
    } else if (c < 0x20) {
      out[o++] = ' ';
    } else {
      out[o++] = (char)c;
    }
  }
  o += (size_t)snprintf(out + o, sizeof out - o, "\"}\n");
  write_status_raw(out, o);
}

static int l_panic(lua_State *L) {
  const char *m = lua_tostring(L, -1);
  write_internal_error(m ? m : "Lua panic");
  _exit(70);
  return 0;
}

static int msg_handler(lua_State *L) {
  const char *m = lua_tostring(L, 1);
  if (m == NULL) m = "(error object is not a string)";
  luaL_traceback(L, L, m, 1);
  return 1;
}

static void setfield_str(lua_State *L, const char *k, const char *v) {
  if (v) {
    lua_pushstring(L, v);
    lua_setfield(L, -2, k);
  }
}

static void setfield_int(lua_State *L, const char *k, long long v) {
  lua_pushinteger(L, (lua_Integer)v);
  lua_setfield(L, -2, k);
}

static void push_opts(lua_State *L, const Opts *o) {
  lua_newtable(L);
  setfield_str(L, "mode", o->mode);
  setfield_str(L, "runtime", o->runtime);
  setfield_str(L, "job", o->job);
  setfield_str(L, "rocks", o->rocks);
  setfield_str(L, "luarocks", o->luarocks);
  setfield_int(L, "timeout_ms", o->timeout_ms);
  setfield_int(L, "mem_bytes", o->mem_bytes);
  setfield_int(L, "fsize_bytes", o->fsize_bytes);
  setfield_int(L, "out_bytes", o->out_bytes);
  setfield_int(L, "result_bytes", o->result_bytes);
}

static void preload(lua_State *L, const char *name, lua_CFunction f) {
  luaL_getsubtable(L, LUA_REGISTRYINDEX, LUA_PRELOAD_TABLE);
  lua_pushcfunction(L, f);
  lua_setfield(L, -2, name);
  lua_pop(L, 1);
}

static void print_version(void) {
  printf("{\"luart\":\"%s\",\"lua\":\"%s\",\"abi\":\"%s\",\"pointerBits\":%d}\n", LUART_VERSION,
         LUA_RELEASE, abi_name(), (int)(sizeof(void *) * 8));
}

/* ------------------------------------------------------------------ */
/* main                                                                */
/* ------------------------------------------------------------------ */

int main(int argc, char **argv) {
  Opts o;
  parse_args(argc, argv, &o);

  if (strcmp(o.mode, "version") == 0) {
    print_version();
    return 0;
  }
  int is_exec = strcmp(o.mode, "exec") == 0;
  int is_rocks = strcmp(o.mode, "rocks") == 0;
  if (!is_exec && !is_rocks) die_usage("unknown mode");
  if (!o.runtime) die_usage("--runtime is required");
  if (is_exec && !o.job) die_usage("--job is required");

  /* Own process group: the host can kill the whole group on cancel. */
  setpgid(0, 0);
  umask(077);
  signal(SIGPIPE, SIG_IGN);
  signal(SIGXFSZ, SIG_IGN);

  if (is_exec) {
    snprintf(g_status_path, sizeof g_status_path, "%s/status.json", o.job);
    /* Always leave a pid file: lets the host reap orphans after a crash. */
    char pidpath[PATH_MAX];
    snprintf(pidpath, sizeof pidpath, "%s/runner.pid", o.job);
    FILE *pf = fopen(pidpath, "w");
    if (pf) {
      fprintf(pf, "%d\n", (int)getpid());
      fclose(pf);
    }
  }

  /* Resource limits (best-effort backstops; the allocator and timers are
     the precise enforcement). */
  struct rlimit rl;
  rl.rlim_cur = rl.rlim_max = 0;
  setrlimit(RLIMIT_CORE, &rl);
  if (o.fsize_bytes > 0) {
    rl.rlim_cur = rl.rlim_max = (rlim_t)o.fsize_bytes;
    setrlimit(RLIMIT_FSIZE, &rl);
  }
  rl.rlim_cur = rl.rlim_max = 256;
  setrlimit(RLIMIT_NOFILE, &rl);
  if (o.timeout_ms > 0) {
    rlim_t secs = (rlim_t)(o.timeout_ms / 1000) + 5;
    rl.rlim_cur = secs;
    rl.rlim_max = secs + 2;
    setrlimit(RLIMIT_CPU, &rl);

    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_handler = on_alarm;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGALRM, &sa, NULL);
    struct itimerval it;
    it.it_interval.tv_sec = it.it_interval.tv_usec = 0;
    it.it_value.tv_sec = (time_t)(o.timeout_ms / 1000);
    it.it_value.tv_usec = (suseconds_t)((o.timeout_ms % 1000) * 1000);
    if (it.it_value.tv_sec == 0 && it.it_value.tv_usec == 0) it.it_value.tv_usec = 1000;
    setitimer(ITIMER_REAL, &it, NULL);
  }
  {
    struct sigaction st;
    memset(&st, 0, sizeof st);
    st.sa_handler = on_term;
    sigemptyset(&st.sa_mask);
    sigaction(SIGTERM, &st, NULL);
  }

  /* Lua state with a hard memory ceiling. */
  g_mem.limit = o.mem_bytes > 0 ? (size_t)o.mem_bytes : 0;
  if (g_mem.limit != 0 && g_mem.limit < (4u << 20)) g_mem.limit = 4u << 20;
  lua_State *L = lua_newstate(l_alloc, &g_mem);
  if (!L) {
    fprintf(stderr, "luart: cannot create Lua state\n");
    write_internal_error("cannot create Lua state");
    return 70;
  }
  lua_atpanic(L, l_panic);
  luaL_openlibs(L);
  lua_warning(L, "@off", 0);

  preload(L, "lfs", luaopen_lfs);
  preload(L, "zlib", luaopen_zlib);
  preload(L, "md5", luaopen_md5);
  preload(L, "posix", luaopen_posix);

  lua_sethook(L, count_hook, LUA_MASKCOUNT, 1000);

  /* Load the (trusted) Lua side with a neutral chunk name. */
  char path[PATH_MAX];
  snprintf(path, sizeof path, "%s/%s", o.runtime, is_exec ? "bootstrap.lua" : "rocks_main.lua");
  size_t len = 0;
  char *src = read_file(path, &len);
  if (!src) {
    fprintf(stderr, "luart: cannot read %s: %s\n", path, strerror(errno));
    write_internal_error("runtime bootstrap is missing");
    return 70;
  }
  int rc = luaL_loadbuffer(L, src, len, is_exec ? "=bootstrap" : "=rocks_main");
  free(src);
  if (rc != LUA_OK) {
    const char *m = lua_tostring(L, -1);
    fprintf(stderr, "luart: %s\n", m ? m : "load error");
    write_internal_error(m ? m : "load error");
    return 70;
  }

  lua_pushcfunction(L, msg_handler);
  lua_insert(L, -2); /* handler below chunk */
  push_luart_table(L);
  push_opts(L, &o);
  int nargs = 2;
  if (is_rocks) {
    for (int i = o.first_extra; i < argc; i++) {
      lua_pushstring(L, argv[i]);
      nargs++;
    }
  }
  rc = lua_pcall(L, nargs, 1, -(nargs + 2));
  if (rc != LUA_OK) {
    const char *m = lua_tostring(L, -1);
    fprintf(stderr, "luart: internal error: %s\n", m ? m : "(unknown)");
    if (is_exec) write_internal_error(m ? m : "unknown internal error");
    fflush(NULL);
    _exit(70);
  }
  int code = (int)lua_tointeger(L, -1);
  fflush(NULL);
  _exit(code); /* skip lua_close: teardown of a big heap can take a while */
}
