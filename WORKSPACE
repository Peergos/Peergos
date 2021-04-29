load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

http_archive(
  name = "io_bazel_rules_gwt",
  url = "https://github.com/bazelbuild/rules_gwt/archive/0.1.3.tar.gz",
  sha256 = "3f017bd2f7734e259535da0bcc75398b883dda6da6b657dfa84bd02fab0a6916",
  strip_prefix = "rules_gwt-0.1.3",
)
load("@io_bazel_rules_gwt//gwt:gwt.bzl", "gwt_repositories")
gwt_repositories()