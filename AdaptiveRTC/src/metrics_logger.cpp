// ============================================================================
// metrics_logger.cpp - Implementation
// ============================================================================

#include "metrics_logger.h"
#include "sender.h"
#include "receiver.h"
#include "network_simulator.h"
#include <sstream>
#include <iomanip>

namespace adaptive_rtc {

MetricsLogger::MetricsLogger(const std::string& output_file)
    : log_file_(output_file),
      is_open_(log_file_.is_open()),
      header_written_(false)
{
}

MetricsLogger::~MetricsLogger() {
    closeLog();
}

void MetricsLogger::logSnapshot(
    uint64_t timestamp_ms,
    const Sender& sender,
    const Receiver& receiver,
    const NetworkSimulator& network)
{
    if (!is_open_) return;
    
    // Write header on first log
    if (!header_written_) {
        writeHeader();
    }
    
    // Collect all metrics
    std::ostringstream oss;
    oss << std::fixed << std::setprecision(2);
    
    oss << timestamp_ms << ","
        << sender.getTotalPacketsSent() << ","
        << sender.getCurrentRate() << ","
        << receiver.getTotalPacketsReceived() << ","
        << receiver.getTotalPacketsLost() << ","
        << receiver.getEstimatedRTT() << ","
        << static_cast<int>(receiver.getECSStatus()) << ","
        << receiver.getJitterBuffer().getCurrentDepth() << ","
        << network.getStatistics().getLossRate() << ","
        << network.getStatistics().getAverageDelay();
    
    logRaw(oss.str());
}

void MetricsLogger::logRaw(const std::string& csv_row) {
    if (!is_open_) return;
    
    log_file_ << csv_row << "\n";
}

void MetricsLogger::flush() {
    if (is_open_) {
        log_file_.flush();
    }
}

void MetricsLogger::closeLog() {
    if (is_open_) {
        flush();
        log_file_.close();
        is_open_ = false;
    }
}

void MetricsLogger::writeHeader() {
    if (!is_open_) return;
    
    log_file_ << "Time_ms,"
              << "Tx_Packets,"
              << "Tx_Rate_bps,"
              << "Rx_Packets,"
              << "Rx_Lost,"
              << "RTT_ms,"
              << "ECS_Status,"
              << "Buffer_Depth,"
              << "Network_Loss_Pct,"
              << "Network_Delay_us\n";
    
    header_written_ = true;
    flush();
}

}  // namespace adaptive_rtc
