.section .text
.globl _start

.equ UART_BASE, 0x310b0000
.equ UART_THR,  0
.equ UART_IER,  4
.equ UART_FCR,  8
.equ UART_LCR,  12
.equ UART_LSR,  20

_start:
  li s0, UART_BASE
  jal ra, uart_init

  li a0, '['
  jal ra, uart_putc
  li a0, 'h'
  jal ra, uart_putc
  li a0, 'a'
  jal ra, uart_putc
  li a0, 'r'
  jal ra, uart_putc
  li a0, 't'
  jal ra, uart_putc

  li a0, '0'
  jal ra, uart_putc
  li a0, 'x'
  jal ra, uart_putc

  csrr t0, mhartid
  mv a0, t0
  jal ra, uart_put_hex64

  li a0, ']'
  jal ra, uart_putc
  li a0, ' '
  jal ra, uart_putc
  li a0, 'u'
  jal ra, uart_putc
  li a0, 'a'
  jal ra, uart_putc
  li a0, 'r'
  jal ra, uart_putc
  li a0, 't'
  jal ra, uart_putc
  li a0, ' '
  jal ra, uart_putc
  li a0, 'o'
  jal ra, uart_putc
  li a0, 'k'
  jal ra, uart_putc
  li a0, '\n'
  jal ra, uart_putc

loop:
  j loop

uart_init:
  # Enable DLAB to program baud divisor.
  li t0, 0x80
  sb t0, UART_LCR(s0)

  # 50MHz / (16 * 115200) ~= 27 (0x1b)
  li t0, 0x1b
  sb t0, UART_THR(s0)   # DLL (DLAB=1)
  sb zero, UART_IER(s0) # DLM (DLAB=1)

  # 8N1, disable UART interrupts, and enable/clear FIFO.
  li t0, 0x03
  sb t0, UART_LCR(s0)
  sb zero, UART_IER(s0)
  li t0, 0x07
  sb t0, UART_FCR(s0)
  ret

uart_putc:
4:
  lbu t0, UART_LSR(s0)
  andi t0, t0, 0x20
  beqz t0, 4b
  sb a0, UART_THR(s0)
  ret

uart_put_hex64:
  mv t5, ra
  mv t4, a0
  li t2, 60
  li t3, 0
5:
  srl t0, t4, t2
  andi t0, t0, 0xf
  bnez t3, 8f
  beqz t0, 9f
  li t3, 1
8:
  li t1, 10
  bltu t0, t1, 6f
  addi a0, t0, 87
  j 7f
6:
  addi a0, t0, 48
7:
  jal ra, uart_putc
9:
  addi t2, t2, -4
  bgez t2, 5b
  bnez t3, 10f
  li a0, '0'
  jal ra, uart_putc
10:
  mv ra, t5
  ret
