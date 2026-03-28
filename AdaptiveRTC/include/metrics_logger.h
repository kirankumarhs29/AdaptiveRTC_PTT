// ============================================================================
// metrics_logger.h - Logging and Metrics Tracking
// ============================================================================
//
// PURPOSE:
// Record system metrics to CSV for analysis and validation.
//
// ============================================================================

#pragma once

#include "packet.h"
#include <string>
#include <fstream>
#include <cstdint>

namespace adaptive_rtc {

// Forward declarations
class Sender;
class Receiver;
class NetworkSimulator;

class MetricsLogger {
public:
    /// Constructor
    ///
    /// Args:
    ///   output_file - path to CSV file for logging
    explicit MetricsLogger(const std::string& output_file);
    
    /// Destructor ensures file is closed
    ~MetricsLogger();
    
    // ========================================================================
    // LOGGING
    // ========================================================================
    
    /// Log system state at a specific time
    ///
    /// Args:
    ///   timestamp_ms - simulation time in milliseconds
    ///   sender - sender object
    ///   receiver - receiver object
    ///   network - network simulator
    void logSnapshot(
        uint64_t timestamp_ms,
        const Sender& sender,
        const Receiver& receiver,
        const NetworkSimulator& network);
    
    /// Manually log a data row
    void logRaw(const std::string& csv_row);
    
    /// Flush buffered data to file
    void flush();
    
    /// Close logging file
    void closeLog();

private:
    std::ofstream log_file_;
    bool is_open_ = false;
    bool header_written_ = false;
    
    /// Write CSV header
    void writeHeader();
};

}  // namespace adaptive_rtc
