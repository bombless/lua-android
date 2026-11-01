"""A tiny LuaRocks-compatible server tree (manifest + rockspecs + .src.rock zips) for tests."""
import io, os, tarfile, zipfile

def rockspec_text(name, version, deps=(), modules=None, build_type="builtin", extra_build="", external=""):
    mods = modules or {name: "%s.lua" % name}
    mod_lines = ",\n".join('      [%r] = %r' % (k, v) if isinstance(v, str) else '      [%r] = %s' % (k, v) for k, v in mods.items())
    mod_lines = mod_lines.replace("'", '"')
    deps_text = ", ".join('"%s"' % d for d in ("lua >= 5.1",) + tuple(deps))
    return f'''package = "{name}"
version = "{version}"
source = {{ url = "https://example.invalid/{name}-{version.split('-')[0]}.tar.gz", dir = "{name}-{version.split('-')[0]}" }}
description = {{ summary = "{name} test rock", license = "MIT" }}
dependencies = {{ {deps_text} }}
{external}
build = {{
   type = "{build_type}",
   modules = {{
{mod_lines}
   }},
{extra_build}
}}
'''

def src_rock(name, version, files, spec):
    """A .src.rock is a zip holding the rockspec plus the source archive named by source.url."""
    ver = version.split("-")[0]
    tar_buf = io.BytesIO()
    with tarfile.open(fileobj=tar_buf, mode="w:gz") as t:
        for path, data in files.items():
            info = tarfile.TarInfo("%s-%s/%s" % (name, ver, path))
            raw = data.encode() if isinstance(data, str) else data
            info.size = len(raw)
            t.addfile(info, io.BytesIO(raw))
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("%s-%s.rockspec" % (name, version), spec)
        z.writestr("%s-%s.tar.gz" % (name, ver), tar_buf.getvalue())
    return buf.getvalue()

def build_repo(root, rocks=None):
    """rocks: {name: {version: (rockspec_text, {relative_path: content})}}"""
    rocks = rocks or default_rocks()
    os.makedirs(root, exist_ok=True)
    repo_lines = []
    for name, versions in rocks.items():
        repo_lines.append("   [%r] = {" % name)
        for version, (spec, files) in versions.items():
            with open(os.path.join(root, "%s-%s.rockspec" % (name, version)), "w") as f:
                f.write(spec)
            with open(os.path.join(root, "%s-%s.src.rock" % (name, version)), "wb") as f:
                f.write(src_rock(name, version, files, spec))
            repo_lines.append('      [%r] = { { arch = "rockspec" }, { arch = "src" } },' % version)
        repo_lines.append("   },")
    manifest = "commands = {}\nmodules = {}\nrepository = {\n" + "\n".join(repo_lines) + "\n}\n"
    manifest = manifest.replace("'", '"')
    with open(os.path.join(root, "manifest-5.4"), "w") as f:
        f.write(manifest)

def default_rocks():
    return {
        "helperlib": {
            "1.0-1": (rockspec_text("helperlib", "1.0-1"), {"helperlib.lua": 'return { double = function(x) return x * 2 end, version = "1.0" }\n'}),
        },
        "purelua": {
            "1.0-1": (rockspec_text("purelua", "1.0-1", deps=["helperlib >= 1.0"],
                                    modules={"purelua": "purelua.lua", "purelua.util": "src/util.lua"}),
                      {"purelua.lua": 'local h = require("helperlib")\nlocal util = require("purelua.util")\nreturn { answer = function() return util.add(h.double(20), 2) end, version = "1.0" }\n',
                       "src/util.lua": "return { add = function(a, b) return a + b end }\n"}),
            "1.1-1": (rockspec_text("purelua", "1.1-1", deps=["helperlib >= 1.0"], modules={"purelua": "purelua.lua"}),
                      {"purelua.lua": 'return { answer = function() return 43 end, version = "1.1" }\n'}),
        },
        "nativemod": {
            "1.0-1": (rockspec_text("nativemod", "1.0-1", modules={"nativemod": "nativemod.c"}), {"nativemod.c": "int luaopen_nativemod(void *L) { return 0; }\n"}),
        },
        "makerock": {
            "1.0-1": (rockspec_text("makerock", "1.0-1", build_type="make", modules={}), {"Makefile": "all:\n\techo hi\n"}),
        },
        "needsnative": {
            "1.0-1": (rockspec_text("needsnative", "1.0-1", deps=["nativemod"], modules={"needsnative": "needsnative.lua"}), {"needsnative.lua": "return {}\n"}),
        },
    }
