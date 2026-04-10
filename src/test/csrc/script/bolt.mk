# BOLT PGO Configuration and Logic
# This file contains BOLT binary detection and PGO workflow implementation
# This is writen by AI, try no to look too deep at it. Might gives you nightmare

# Configuration variables
PGO_MAX_CYCLE ?= 11000
# Note: A larger workload will yield a better result
PGO_WORKLOAD ?= $(C_SRC_DIR)/app/uart_min.bin

# BOLT-specific linker flags
PGO_LDFLAGS := -Wl,--emit-relocs

# Auto-detect BOLT binary if not specified
ifndef BOLT_BIN
  # Try to find BOLT binary in order of preference
  BOLT_BIN := $(shell which llvm-bolt 2>/dev/null || \
                  which llvm-bolt-24 2>/dev/null || \
                  which llvm-bolt-23 2>/dev/null || \
                  which llvm-bolt-22 2>/dev/null || \
                  which llvm-bolt-21 2>/dev/null || \
                  which llvm-bolt-20 2>/dev/null || \
                  which llvm-bolt-19 2>/dev/null || \
                  which llvm-bolt-18 2>/dev/null || \
                  echo "")
endif

# Auto-detect perf2bolt binary
ifndef PERF2BOLT
  PERF2BOLT := $(shell which perf2bolt 2>/dev/null || \
                       which perf2bolt-24 2>/dev/null || \
                       which perf2bolt-23 2>/dev/null || \
                       which perf2bolt-22 2>/dev/null || \
                       which perf2bolt-21 2>/dev/null || \
                       which perf2bolt-20 2>/dev/null || \
                       which perf2bolt-19 2>/dev/null || \
                       which perf2bolt-18 2>/dev/null || \
                       echo "")
endif

# Check if BOLT is available
ifeq ($(BOLT),1)
  ifeq ($(BOLT_BIN),)
    $(error BOLT=1 but BOLT binary not found. Please install llvm or set BOLT_BIN=/path/to/llvm-bolt)
  endif
  BOLT_FOUND := 1
  $(info Found BOLT: $(BOLT_BIN))
else
  BOLT_FOUND := 0
endif

# BOLT optimization flags
# I tried a few combinations and this is best under most conditions
BOLT_FLAGS := -reorder-blocks=ext-tsp -reorder-functions=cdsort
# Profile data paths
PERF_DATA := $(BUILD_DIR)/perf.data
PROFILE_FDATA := $(BUILD_DIR)/prof.fdata

# Original and instrumented binary paths
SIM_ORIG := $(SIM).orig
SIM_INSTR := $(SIM).instr

# Build the PGO workload
.PHONY: build-pgo-workload
build-pgo-workload:
	$(MAKE) -C $(C_SRC_DIR)/app uart_min.bin

# Main BOLT PGO workflow
.PHONY: bolt-pgo
bolt-pgo: build-pgo-workload
	@mkdir -p $(BUILD_DIR)
	@if [ ! -f "$(SIM_ORIG)" ]; then \
		echo "Error: $(SIM_ORIG) not found"; \
		exit 1; \
	fi
	@if [ ! -e $(PROFILE_FDATA) ]; then\
		echo "Training with PGO workload..."; \
		(perf record -j any,u -o $(PERF_DATA) -- $(SIM_ORIG) -i $(PGO_WORKLOAD) -c $(PGO_MAX_CYCLE) && \
			[ -n "$(PERF2BOLT)" ] && \
			$(PERF2BOLT) -p $(PERF_DATA) -o $(PROFILE_FDATA) $(SIM_ORIG)) || \
		 (echo "linux-perf/perf2bolt unavailable, fallback to instrumentation-based PGO" && \
			$(BOLT_BIN) $(SIM_ORIG) -instrument --instrumentation-file=$(PROFILE_FDATA) -o $(SIM_INSTR) && \
			$(SIM_INSTR) -i $(PGO_WORKLOAD) -c $(PGO_MAX_CYCLE));\
	else\
		echo '\033[32mUsing an existing profile data. run `make clean-pgo` to re-gererate\033[0m';\
	fi
	@echo "Applying BOLT..."
	@$(BOLT_BIN) $(SIM_ORIG) -o $(SIM) -data $(PROFILE_FDATA) $(BOLT_FLAGS)
	@rm -f $(SIM_INSTR)

# Clean PGO artifacts
.PHONY: clean-pgo
clean-pgo:
	$(RM) $(PERF_DATA) $(PROFILE_FDATA)
	$(RM) $(SIM) $(SIM_ORIG) $(SIM_INSTR)
