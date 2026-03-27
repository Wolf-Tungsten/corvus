This is a mini platform config for opensbi suitble
for corvus development without dts

Usage:
In opensbi, `make PLATFORM_DIR=[This dir] FW_PAYLOAD_PATH=[path to rtthread.bin] FW_TEXT_START=0x80000000 FW_PAYLOAD_OFFSET=0x200000 LLVM=[path to toolchains bin]`
