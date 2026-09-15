# Matches scripts/build-release.sh in the supplied repository. Do not float this:
# the 802.11p HMAC adapter depends on private ESP-IDF structures and symbols.
FROM espressif/idf:v6.1-dev@sha256:d63012d35027ba995ffe3f1182407670cf84bd9e95b3242d3ac5e5d6d53b8ff9
ENV CARGO_HOME=/opt/rust/cargo RUSTUP_HOME=/opt/rust/rustup
ENV PATH=/opt/rust/cargo/bin:${PATH}
RUN curl --proto '=https' --tlsv1.2 -fsSL https://sh.rustup.rs -o /tmp/rustup.sh \
    && sh /tmp/rustup.sh -y --profile minimal --default-toolchain 1.88.0 \
       --component rustfmt,clippy --target riscv32imac-unknown-none-elf \
    && rm /tmp/rustup.sh
WORKDIR /project
