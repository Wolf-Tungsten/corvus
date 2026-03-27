/*
 * SPDX-License-Identifier: BSD-2-Clause
 *
 * Copyright (c) 2019 Western Digital Corporation or its affiliates.
 */

#include "sbi/sbi_console.h"
#include "sbi/sbi_scratch.h"
#include <sbi/riscv_asm.h>
#include <sbi/riscv_encoding.h>
#include <sbi/sbi_const.h>
#include <sbi/sbi_platform.h>

/*
 * Include these files as needed.
 * See objects.mk PLATFORM_xxx configuration parameters.
 */
#include <sbi_utils/ipi/aclint_mswi.h>
#include <sbi_utils/irqchip/plic.h>
#include <sbi_utils/serial/uart8250.h>
#include <sbi_utils/timer/aclint_mtimer.h>

// Same as baseAddress in StandAlonePLIC
#define CORVUS_PLIC_ADDR		0x3c000000
#define CORVUS_PLIC_SIZE		0x4000000
// Same as extIntrNum in StandAlonePLIC
#define CORVUS_PLIC_NUM_SOURCES	64
// Same as p.numSCore + 1 (for master)
#define CORVUS_HART_COUNT		8
#define CORVUS_CLINT_ADDR		0x38000000
#define CORVUS_CLINT_MTIMER_FREQ	1000000
#define CORVUS_CLINT_MSWI_ADDR	(CORVUS_CLINT_ADDR + \
					 					CLINT_MSWI_OFFSET)
// CLINT -> MTIMECMP 
#define CORVUS_CLINT_MTIMER_ADDR	(CORVUS_CLINT_ADDR + \
					 					CLINT_MTIMER_OFFSET)
#define CORVUS_UART_ADDR		0x310b0000
#define CORVUS_UART_INPUT_FREQ	50000000
#define CORVUS_UART_BAUDRATE	115200
#define CORVUS_UART_REGSHIFT    2
#define CORVUS_UART_REGIOWIDTH  4

static struct plic_data plic = {
	.addr = CORVUS_PLIC_ADDR,
	.size = CORVUS_PLIC_SIZE,
	.num_src = CORVUS_PLIC_NUM_SOURCES,
	.context_map = {
		[0] = { 0, 1 },
		[1] = { 2, 3 },
		[2] = { 4, 5 },
		[3] = { 6, 7 },
		[4] = { 8, 9 },
		[5] = { 10, 11 },
		[6] = { 12, 13 },
		[7] = { 14, 15 },
	},
};


static struct aclint_mswi_data mswi = {
	.addr = CORVUS_CLINT_MSWI_ADDR,
	.size = ACLINT_MSWI_SIZE,
	.first_hartid = 0,
	.hart_count = CORVUS_HART_COUNT,
};

static struct aclint_mtimer_data mtimer = {
	.mtime_freq = CORVUS_CLINT_MTIMER_FREQ,
	.mtime_addr = CORVUS_CLINT_MTIMER_ADDR +
		      ACLINT_DEFAULT_MTIME_OFFSET,
	.mtime_size = ACLINT_DEFAULT_MTIME_SIZE,
	.mtimecmp_addr = CORVUS_CLINT_MTIMER_ADDR +
			 ACLINT_DEFAULT_MTIMECMP_OFFSET,
	.mtimecmp_size = ACLINT_DEFAULT_MTIMECMP_SIZE,
	.first_hartid = 0,
	.hart_count = CORVUS_HART_COUNT,
	.has_64bit_mmio = true,
};

/*
 * Platform early initialization.
 */
static int corvus_early_init(bool cold_boot)
{
	int rc;

	if (!cold_boot)
		return 0;

	rc = uart8250_init(CORVUS_UART_ADDR, CORVUS_UART_INPUT_FREQ,
			   CORVUS_UART_BAUDRATE, CORVUS_UART_REGSHIFT,
			   CORVUS_UART_REGIOWIDTH, 0, 0);
	if (rc)
		return rc;
	// Speed up sim
	sbi_scratch_thishart_ptr()->options = sbi_scratch_thishart_ptr()-> options | SBI_SCRATCH_NO_BOOT_PRINTS;
	return aclint_mswi_cold_init(&mswi);
}

/*
 * Initialize the platform interrupt controller during cold boot.
 */
static int corvus_irqchip_init(void)
{
	//FIXME: This is problematic. Since every plic thinks it serves HART0
	return plic_cold_irqchip_init(&plic);
}

/*
 * Initialize platform timer during cold boot.
 */
static int corvus_timer_init(void)
{
	return aclint_mtimer_cold_init(&mtimer, NULL);
}
static int corvus_final_init(bool cold_boot){
	// each HART has a different scratch
	// we can change the sbi_scratch_thishart_ptr()->next_addr based on
	// hart id
	//sbi_printf("\n!!scratch is: %p\n",sbi_scratch_thishart_ptr());
	return 0;
}

/*
 * Platform descriptor.
 */
const struct sbi_platform_operations platform_ops = {
	.early_init	= corvus_early_init,
	.irqchip_init	= corvus_irqchip_init,
	.timer_init	= corvus_timer_init,
	.final_init     = corvus_final_init
};
const struct sbi_platform platform = {
	.opensbi_version	= OPENSBI_VERSION,
	.platform_version	= SBI_PLATFORM_VERSION(0x0, 0x00),
	.name			= "Corvus",
	.features		= SBI_PLATFORM_DEFAULT_FEATURES,
	.hart_count		= CORVUS_HART_COUNT,
	.hart_stack_size	= SBI_PLATFORM_DEFAULT_HART_STACK_SIZE,
	.heap_size		= SBI_PLATFORM_DEFAULT_HEAP_SIZE(1),
	.platform_ops_addr	= (unsigned long)&platform_ops
};
