/*
** Small native modules for the LuaRocks mode of luart:
**   md5    - md5.sumhexa(s) / md5.sum(s)      (LuaRocks verifies source hashes)
**   posix  - the handful of luaposix calls LuaRocks uses (chmod, umask,
**            geteuid, getpwuid, mkdtemp)
** Neither module is reachable from sandboxed job scripts.
*/
#define _GNU_SOURCE
#include <errno.h>
#include <pwd.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include "lauxlib.h"
#include "lua.h"

/* ------------------------------ MD5 (RFC 1321) ------------------------------ */

typedef struct {
  uint32_t a, b, c, d;
  uint64_t bytes;
  unsigned char buf[64];
} Md5;

static const uint32_t K[64] = {
    0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
    0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be, 0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
    0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
    0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
    0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c, 0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
    0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
    0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
    0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1, 0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391};
static const unsigned char R[64] = {7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
                                    5, 9,  14, 20, 5, 9,  14, 20, 5, 9,  14, 20, 5, 9,  14, 20,
                                    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
                                    6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21};

#define ROL(x, n) (((x) << (n)) | ((x) >> (32 - (n))))

static void md5_block(Md5 *m, const unsigned char *p) {
  uint32_t w[16];
  for (int i = 0; i < 16; i++)
    w[i] = (uint32_t)p[i * 4] | ((uint32_t)p[i * 4 + 1] << 8) | ((uint32_t)p[i * 4 + 2] << 16) |
           ((uint32_t)p[i * 4 + 3] << 24);
  uint32_t a = m->a, b = m->b, c = m->c, d = m->d;
  for (int i = 0; i < 64; i++) {
    uint32_t f;
    int g;
    if (i < 16) { f = (b & c) | (~b & d); g = i; }
    else if (i < 32) { f = (d & b) | (~d & c); g = (5 * i + 1) & 15; }
    else if (i < 48) { f = b ^ c ^ d; g = (3 * i + 5) & 15; }
    else { f = c ^ (b | ~d); g = (7 * i) & 15; }
    uint32_t t = d;
    d = c;
    c = b;
    b = b + ROL(a + f + K[i] + w[g], R[i]);
    a = t;
  }
  m->a += a; m->b += b; m->c += c; m->d += d;
}

static void md5_init(Md5 *m) {
  m->a = 0x67452301; m->b = 0xefcdab89; m->c = 0x98badcfe; m->d = 0x10325476;
  m->bytes = 0;
}

static void md5_update(Md5 *m, const unsigned char *p, size_t n) {
  size_t have = (size_t)(m->bytes & 63);
  m->bytes += n;
  if (have) {
    size_t take = 64 - have;
    if (take > n) take = n;
    memcpy(m->buf + have, p, take);
    p += take; n -= take; have += take;
    if (have < 64) return;
    md5_block(m, m->buf);
  }
  while (n >= 64) { md5_block(m, p); p += 64; n -= 64; }
  if (n) memcpy(m->buf, p, n);
}

static void md5_final(Md5 *m, unsigned char out[16]) {
  uint64_t bits = m->bytes * 8;
  unsigned char pad[72] = {0x80};
  size_t have = (size_t)(m->bytes & 63);
  size_t padlen = (have < 56) ? 56 - have : 120 - have;
  unsigned char lenb[8];
  for (int i = 0; i < 8; i++) lenb[i] = (unsigned char)(bits >> (8 * i));
  md5_update(m, pad, padlen);
  md5_update(m, lenb, 8);
  uint32_t v[4] = {m->a, m->b, m->c, m->d};
  for (int i = 0; i < 4; i++)
    for (int j = 0; j < 4; j++) out[i * 4 + j] = (unsigned char)(v[i] >> (8 * j));
}

static int md5_sum(lua_State *L) {
  size_t n;
  const char *s = luaL_checklstring(L, 1, &n);
  Md5 m;
  unsigned char d[16];
  md5_init(&m);
  md5_update(&m, (const unsigned char *)s, n);
  md5_final(&m, d);
  lua_pushlstring(L, (const char *)d, 16);
  return 1;
}

static int md5_sumhexa(lua_State *L) {
  size_t n;
  const char *s = luaL_checklstring(L, 1, &n);
  Md5 m;
  unsigned char d[16];
  char hex[33];
  md5_init(&m);
  md5_update(&m, (const unsigned char *)s, n);
  md5_final(&m, d);
  for (int i = 0; i < 16; i++) snprintf(hex + i * 2, 3, "%02x", d[i]);
  lua_pushlstring(L, hex, 32);
  return 1;
}

int luaopen_md5(lua_State *L) {
  static const luaL_Reg f[] = {{"sum", md5_sum}, {"sumhexa", md5_sumhexa}, {NULL, NULL}};
  luaL_newlib(L, f);
  return 1;
}

/* ------------------------------ posix subset ------------------------------ */

static int parse_rwx(const char *s, mode_t *out) {
  if (strlen(s) != 9) return 0;
  mode_t m = 0;
  static const mode_t bits[9] = {S_IRUSR, S_IWUSR, S_IXUSR, S_IRGRP, S_IWGRP,
                                 S_IXGRP, S_IROTH, S_IWOTH, S_IXOTH};
  static const char ch[3] = {'r', 'w', 'x'};
  for (int i = 0; i < 9; i++) {
    if (s[i] == ch[i % 3]) m |= bits[i];
    else if (s[i] != '-') return 0;
  }
  *out = m;
  return 1;
}

static int push_errno(lua_State *L, const char *what) {
  int e = errno;
  lua_pushnil(L);
  lua_pushfstring(L, "%s: %s", what, strerror(e));
  lua_pushinteger(L, e);
  return 3;
}

static int px_chmod(lua_State *L) {
  const char *path = luaL_checkstring(L, 1);
  mode_t m;
  if (lua_type(L, 2) == LUA_TNUMBER) {
    m = (mode_t)lua_tointeger(L, 2);
  } else if (!parse_rwx(luaL_checkstring(L, 2), &m)) {
    return luaL_error(L, "bad mode string");
  }
  if (chmod(path, m) != 0) return push_errno(L, path);
  lua_pushinteger(L, 0); /* luaposix convention: 0 on success */
  return 1;
}

static int px_umask(lua_State *L) {
  mode_t cur = umask(0);
  umask(cur);
  mode_t allowed = (mode_t)(0777 & ~cur);
  char s[10];
  static const char ch[3] = {'r', 'w', 'x'};
  for (int i = 0; i < 9; i++) s[i] = (allowed & (0400 >> i)) ? ch[i % 3] : '-';
  s[9] = '\0';
  lua_pushstring(L, s);
  return 1;
}

static int px_geteuid(lua_State *L) {
  lua_pushinteger(L, (lua_Integer)geteuid());
  return 1;
}

static int px_getpwuid(lua_State *L) {
  uid_t uid = (uid_t)luaL_checkinteger(L, 1);
  struct passwd *pw = getpwuid(uid);
  lua_newtable(L);
  lua_pushstring(L, pw && pw->pw_name ? pw->pw_name : "app");
  lua_setfield(L, -2, "pw_name");
  return 1;
}

static int px_mkdtemp(lua_State *L) {
  size_t n;
  const char *tpl = luaL_checklstring(L, 1, &n);
  char *buf = (char *)lua_newuserdatauv(L, n + 1, 0);
  memcpy(buf, tpl, n + 1);
  if (mkdtemp(buf) == NULL) return push_errno(L, tpl);
  lua_pushstring(L, buf);
  return 1;
}

int luaopen_posix(lua_State *L) {
  static const luaL_Reg f[] = {{"chmod", px_chmod},     {"umask", px_umask},
                               {"geteuid", px_geteuid}, {"getpwuid", px_getpwuid},
                               {"mkdtemp", px_mkdtemp}, {NULL, NULL}};
  luaL_newlib(L, f);
  return 1;
}
