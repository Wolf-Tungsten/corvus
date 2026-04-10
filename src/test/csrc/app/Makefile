TOOLCHAIN ?= riscv64-linux-gnu-
CC := $(TOOLCHAIN)gcc
OBJCOPY := $(TOOLCHAIN)objcopy
OBJDUMP := $(TOOLCHAIN)objdump

APP ?= uart_min
BIN := $(APP).bin
ELF := $(APP).elf
ASM := $(APP).S
LDS := link.ld

CFLAGS := -march=rv64i_zicsr -mabi=lp64 -nostdlib -nostartfiles -static -ffreestanding -Wl,--no-gc-sections -Wl,--build-id=none

.PHONY: all disasm clean

all: $(BIN)

$(ELF): $(ASM) $(LDS)
	$(CC) $(CFLAGS) -T $(LDS) -o $@ $(ASM)

$(BIN): $(ELF)
	$(OBJCOPY) -S --set-section-flags .bss=alloc,contents -O binary $< $@

disasm: $(ELF)
	$(OBJDUMP) -D $< > $(APP).dump

clean:
	$(RM) $(ELF) $(BIN) $(APP).dump
