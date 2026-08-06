# NPDev Manager headless container proof (CLOSEOUT_PLAN.md §5, I4).
#
# "The same instrument, one layer up" from scripts/quality/firstrun-harness: that harness proves
# the CLI's own README instructions work on a genuinely bare machine. This one proves the
# MANAGER's install path works on a bare machine, mechanically, on every change -- not once by a
# human clicking through five screens (M3's thesis: a private JDK gets `doctor` green with no
# system Java at all, re-proven here instead of by hand).
#
# Deliberately absent from the runtime stage: java, python. `git` IS installed, unlike the
# firstrun-harness's "present only: git + curl" -- here for the OPPOSITE reason: this harness
# proves the Manager needs no git (NPDev versions are installed as an HTTPS zip download, never
# `git clone`), but `npdev doctor`'s own git-present check is unrelated to anything the Manager
# does and would otherwise fail this harness on something outside I4's own scope. (Decision
# recorded 2026-08-05: install git so a real, literal doctor 10/10 is achievable, matching
# CLOSEOUT_PLAN.md's own stated assertion, rather than silently redefining "10/10" downward.)

# ---- builder: compile the Linux release binary. This project's own dev machine is Windows, so
# this stage is also the only way a Linux npdev-manager binary gets produced at all today. ----
FROM rust:1-slim-bookworm AS builder

# Tauri 2 needs these -dev packages to even LINK on Linux (webview + tray/appindicator bindings),
# regardless of whether `--selftest` ever opens a window.
RUN apt-get update && apt-get install -y --no-install-recommends \
      pkg-config libssl-dev \
      libwebkit2gtk-4.1-dev libjavascriptcoregtk-4.1-dev libsoup-3.0-dev \
      libgtk-3-dev libayatana-appindicator3-dev librsvg2-dev \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /src
COPY NPDevManager/Cargo.toml NPDevManager/Cargo.lock NPDevManager/build.rs ./
COPY NPDevManager/src ./src
COPY NPDevManager/fixtures ./fixtures
COPY NPDevManager/capabilities ./capabilities
COPY NPDevManager/icons ./icons
COPY NPDevManager/tauri.conf.json ./tauri.conf.json
COPY NPDevManager/ui ./ui
RUN cargo build --release --target-dir /build

# ---- runtime: the bare machine I4 actually tests ----
FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    NPDEV_MANAGER_HOME=/root/.local/share/npdev

# ca-certificates: HTTPS downloads (Adoptium, python-build-standalone, GitHub) all fail TLS
# verification without it -- absent from a bare ubuntu:24.04 image.
# git: see the header comment above -- present so doctor's own check can pass, not because the
# Manager needs it.
# The libwebkit2gtk/libgtk/etc RUNTIME (non-dev) packages: the compiled binary dynamically links
# against them (DT_NEEDED) -- the dynamic linker resolves those at process start regardless of
# whether a window is ever created, so `--selftest` cannot even launch without them present.
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates git \
      libwebkit2gtk-4.1-0 libjavascriptcoregtk-4.1-0 libsoup-3.0-0 \
      libgtk-3-0 libayatana-appindicator3-1 librsvg2-2 \
 && rm -rf /var/lib/apt/lists/*

COPY --from=builder /build/release/npdev-manager /usr/local/bin/npdev-manager

ENTRYPOINT ["/usr/local/bin/npdev-manager", "--selftest"]
