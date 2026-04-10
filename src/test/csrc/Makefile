# Configurations
VERILATOR_JOBS ?= 6
QUIET ?= 1
EMU_THREADS ?= 16
# Hierarchical Verilation significantly reduces verilation time while slightly decrease simulation speed.
HIER ?= 1
WAVE ?= 0
# PGO, recommended. Runs ~2.5x faster
BOLT ?= 1


.DEFAULT_GOAL:= sim
ROOT := $(abspath ../../..)
RTL_DIR := $(ROOT)/build/rtl
C_SRC_DIR := $(abspath .)
XS_DIFFTEST_DIR := $(ROOT)/XiangShan/difftest
XS_COMMON_DIR := $(XS_DIFFTEST_DIR)/src/test/csrc/common
XS_CONFIG_DIR := $(XS_DIFFTEST_DIR)/config
SIM = $(BUILD_DIR)/VSimTop
BUILD_DIR ?= $(C_SRC_DIR)/obj_dir
VERILATOR ?= verilator

include $(C_SRC_DIR)/script/bolt.mk
CXX_SRCS := $(C_SRC_DIR)/sim_main.cpp \
            $(XS_COMMON_DIR)/common.cpp \
            $(XS_COMMON_DIR)/compress.cpp \
            $(XS_COMMON_DIR)/elfloader.cpp \
            $(XS_COMMON_DIR)/ram.cpp \
            $(XS_COMMON_DIR)/flash.cpp \
            $(XS_CONFIG_DIR)/config.cpp

CFLAGS := -std=c++17 -DNO_GZ_COMPRESSION -DNO_ZSTD_COMPRESSION \
          -I$(XS_COMMON_DIR) -I$(XS_CONFIG_DIR) -I$(C_SRC_DIR)
LDFLAGS := -pthread
ifeq ($(BOLT),1)
  LDFLAGS += $(PGO_LDFLAGS)
endif

VERILATOR_FLAGS := -O3 --sv --cc --exe --top-module SimTop --threads $(EMU_THREADS) \
  --Mdir $(BUILD_DIR) -I$(C_SRC_DIR) -I$(XS_COMMON_DIR) -I$(XS_CONFIG_DIR) \
  -y $(RTL_DIR) -F $(RTL_DIR)/filelist.f -v $(C_SRC_DIR)/DifftestMacros.v \
  -CFLAGS "$(CFLAGS)" -LDFLAGS "$(LDFLAGS)" \
  --verilate-jobs $(VERILATOR_JOBS) \
  -Wno-fatal -Wno-UNOPTFLAT -Wno-BLKANDNBLK -Wno-STMTDLY -Wno-WIDTH \
  --max-num-width 150000 --assert --x-assign unique --output-split 30000 --output-split-cfuncs 30000
VERILATOR_FLAGS += $(if $(filter 1,$(HIER)),--hierarchical $(C_SRC_DIR)/corvus_hier.vlt)
VERILATOR_FLAGS += $(if $(filter 1,$(WAVE)),--trace-fst)


.PHONY: sim clean
sim:
	time $(VERILATOR) $(VERILATOR_FLAGS) $(CXX_SRCS)
ifeq ($(BOLT),1)
	@$(RM) $(SIM)
endif
	time $(MAKE) -C $(BUILD_DIR) -f $(BUILD_DIR)/VSimTop.mk $(if $(filter 1,$(QUIET)),OBJCACHE="@echo + CXX $$< && ccache")
ifeq ($(BOLT),1)
	@echo "BOLT_PGO enabled - saving original binary and applying BOLT optimization..."
	@if [ -f "$(SIM)" ]; then \
		mv $(SIM) $(SIM).orig; \
		$(MAKE) -f $(C_SRC_DIR)/Makefile bolt-pgo; \
	else \
		echo "Error: Simulator binary not found at $(SIM)"; \
		exit 1; \
	fi
endif
	@echo SIM:$(SIM)

clean:
	$(RM) -r $(BUILD_DIR)
	@$(MAKE) -C $(C_SRC_DIR)/app/ clean
