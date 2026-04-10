#include "sim_main.h"
#include "VSimTop.h"
#include "common.h"
#include "flash.h"
#include "ram.h"
#include "verilated.h"
#if VM_TRACE==1
#include "verilated_fst_c.h"
#endif

#include <getopt.h>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <iostream>
#include <signal.h>
#include <string>
#include <filesystem>

static vluint64_t main_time = 0;
double sc_time_stamp() { return static_cast<double>(main_time); }


static void print_help(const char *exe) {
  std::cout << "Usage: " << exe << " [options]\n"
            << "  -i, --image=PATH        memory image (ELF/bin/gz/zst)\n"
            << "  -f, --flash=PATH        flash image (bin)\n"
            << "  -m, --mem-size=SIZE     memory size, e.g. 2GB or 512MB\n"
            << "  -c, --max-cycles=N      stop after N cycles (0 = run forever)\n"
            << "  -r, --reset-cycles=N    number of reset cycles (default 16)\n"
            << "  -w, --wave              enable FST dump (default path sim.fst)\n"
            << "      --wave-file=PATH    waveform output path (requires --wave)\n"
            << "  -l, --log               enable UART log to files\n"
            << "      --log-path=PATH     UART log directory (default: logs)\n"
            << "  -h, --help              show this message\n";
}

static SimConfig parse_args(int argc, char **argv) {
  SimConfig cfg;
  const struct option long_opts[] = {
      {"image", required_argument, nullptr, 'i'},      {"flash", required_argument, nullptr, 'f'},
      {"mem-size", required_argument, nullptr, 'm'},   {"max-cycles", required_argument, nullptr, 'c'},
      {"reset-cycles", required_argument, nullptr, 'r'}, {"wave", no_argument, nullptr, 'w'},
      {"wave-file", required_argument, nullptr, 0},    {"log", no_argument, nullptr, 'l'},
      {"log-path", required_argument, nullptr, 0},     {"help", no_argument, nullptr, 'h'}, {0, 0, 0, 0}};

  while (true) {
    int idx = 0;
    int opt = getopt_long(argc, argv, "hi:f:m:c:r:wl", long_opts, &idx);
    if (opt == -1) break;
    switch (opt) {
      case 'i': cfg.image = optarg; break;
      case 'f': cfg.flash = optarg; break;
      case 'm': cfg.mem_size_str = optarg; break;
      case 'c': cfg.max_cycles = std::strtoull(optarg, nullptr, 0); break;
      case 'r': cfg.reset_cycles = std::strtoull(optarg, nullptr, 0); break;
      case 'w': cfg.enable_wave = true; break;
      case 'l': cfg.enable_log = true; break;
      case 'h': print_help(argv[0]); std::exit(0);
      case 0:
        if (std::string(long_opts[idx].name) == "wave-file") {
          cfg.wave_path = optarg;
        } else if (std::string(long_opts[idx].name) == "log-path") {
          cfg.log_path = optarg;
        }
        break;
      default:
        print_help(argv[0]);
        std::exit(1);
    }
  }

  if (!cfg.mem_size_str.empty()) {
    cfg.mem_size_bytes = parse_ramsize(cfg.mem_size_str.c_str());
  }
  return cfg;
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  SimConfig cfg = parse_args(argc, argv);

  if (signal(SIGINT, sig_handler) == SIG_ERR) {
    printf("\ncan't catch SIGINT\n");
  }

  common_init(argv[0]);
  init_ram(cfg.image.empty() ? nullptr : cfg.image.c_str(), cfg.mem_size_bytes);
  init_flash(cfg.flash.empty() ? nullptr : cfg.flash.c_str());

  // Setup UART logs
  if (cfg.enable_log) {
    std::filesystem::create_directories(cfg.log_path);
    init_all_uart_logs<VSimTop>(cfg.log_path);
  }

  VSimTop *dut = new VSimTop();
#if VM_TRACE==1
  VerilatedFstC *tfp = nullptr;
  if (cfg.enable_wave) {
    Verilated::traceEverOn(true);
    tfp = new VerilatedFstC();
    dut->trace(tfp, 99);
    tfp->open(cfg.wave_path.c_str());
  }
#else
  if (cfg.enable_wave){
    std::cout<<"Trace is not enabled in verilator" << std::endl;
    exit(1);
  }
#endif
  auto step_half = [&](int clk_val) {
    dut->clock = clk_val;
    dut->eval();
#if VM_TRACE==1
    if (tfp) tfp->dump(main_time);
#endif
    main_time += 5; // 5 time units per half cycle keeps timestamps monotonic
  };

  auto tick = [&]() {
    step_half(1);
    step_half(0);
  };

  dut->reset = 0;
  // dut->io_uart_in_ch = 0;
  step_half(0);
  dut->reset = 1;
  step_half(0);
  for (uint64_t i = 0; i < cfg.reset_cycles; ++i) {
    tick();
  }
  dut->reset = 0;

  uint64_t cycles = 0;
  const auto sim_start_time = std::chrono::steady_clock::now();
  while (!Verilated::gotFinish() && (cfg.max_cycles == 0 || cycles < cfg.max_cycles)) {
    if (signal_num) break;
    // dut->io_uart_in_ch = 0;

    tick();
    cycles++;
    print_all_uart_outs(dut);
    if (cfg.enable_log) log_all_uart_outs(dut);
    // if (dut->io_uart_in_valid) {
    //   int available = std::cin.rdbuf()->in_avail();
    //   if (available > 0) {
    //     char ch = 0;
    //     std::cin.get(ch);
    //     dut->io_uart_in_ch = static_cast<uint8_t>(ch);
    //   }
    // }
  }

  if (cfg.max_cycles && cycles >= cfg.max_cycles) {
    std::cout << "\n[sim] reached max cycles " << cfg.max_cycles << "\n";
  }

  if (signal_num) {
    const auto sim_elapsed = std::chrono::duration<double>(std::chrono::steady_clock::now() - sim_start_time).count();
    eprintf(ANSI_COLOR_YELLOW "SOME SIGNAL STOPS THE PROGRAM\n" ANSI_COLOR_RESET);
    eprintf(ANSI_COLOR_MAGENTA "cycleCnt = %'" PRIu64 "\n" ANSI_COLOR_RESET, cycles);
    eprintf(ANSI_COLOR_MAGENTA "simTime = %.3f s\n" ANSI_COLOR_RESET, sim_elapsed);
    eprintf(ANSI_COLOR_MAGENTA "%.1f cycle/s\n" ANSI_COLOR_RESET, cycles/sim_elapsed);
  }
#if VM_TRACE==1
  if (tfp) {
    tfp->close();
    delete tfp;
  }
#endif

  flash_finish();
  delete dut;
  return 0;
}
