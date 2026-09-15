# Sourced by the Nix shell. All dependency paths below are set by flake.nix.
# Keep Python wheels outside /nix/store; pin every package and accepted hash.
export IDF_TOOLS_PATH="${XDG_CACHE_HOME:-$HOME/.cache}/cits-to-go/idf-tools"
export IDF_PYTHON_ENV_PATH="${XDG_CACHE_HOME:-$HOME/.cache}/cits-to-go/python-$CITS_PYTHON_KEY"
mkdir -p "$IDF_TOOLS_PATH" || return 1
if [[ ! -f "$IDF_PYTHON_ENV_PATH/.ready" ]]; then
  "$CITS_NIX_PYTHON" -m venv "$IDF_PYTHON_ENV_PATH" || return 1
  "$IDF_PYTHON_ENV_PATH/bin/python" -m pip install \
    --index-url https://pypi.org/simple \
    --disable-pip-version-check --require-hashes --only-binary=:all: \
    -r "$CITS_PYTHON_BOOTSTRAP" || return 1
  "$IDF_PYTHON_ENV_PATH/bin/python" -m pip install \
    --index-url https://pypi.org/simple \
    --disable-pip-version-check --require-hashes --no-build-isolation \
    --only-binary=:all: --no-binary=esptool \
    -r "$CITS_PYTHON_REQUIREMENTS" || return 1
  "$IDF_PYTHON_ENV_PATH/bin/python" -m pip check || return 1
  touch "$IDF_PYTHON_ENV_PATH/.ready" || return 1
fi
export PATH="$IDF_PYTHON_ENV_PATH/bin:$IDF_PATH/tools:$PATH"
export CITS_BUILD_DIR=build-nix
# Host tests must survive IDF fullclean and must not populate its build tree.
export CARGO_TARGET_DIR="$PWD/target/nix-host"
# IDF has no managed components here. Avoid network resolution of optional
# component-manager dependencies; its Python requirements are still installed.
export IDF_COMPONENT_MANAGER=0
export IDF_PYTHON_CHECK_CONSTRAINTS=no
export IDF_SKIP_CHECK_SUBMODULES=1
export IDF_CCACHE_ENABLE=0
export ESP_IDF_VERSION=6.1.0
