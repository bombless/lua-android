return {
  apiVersion = 1,
  jobId = "0123456789abcdef0123456789abcdef",
  scriptName = "my script \195\169.lua",
  workdir = "output/sub",
  args = {"a b", "\034q\034\092", "\195\169\010\000", ""},
  env = {["K"] = "v\092", ["EMPTY"] = "", ["Z_9"] = "line1\010line2"},
}
