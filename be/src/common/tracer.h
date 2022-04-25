// This file is licensed under the Elastic License 2.0. Copyright 2022-present, StarRocks Limited.

#pragma once

#include <opentelemetry/exporters/jaeger/jaeger_exporter.h>
#include <opentelemetry/sdk/trace/simple_processor.h>
#include <opentelemetry/sdk/trace/tracer_provider.h>
#include <opentelemetry/trace/provider.h>

namespace starrocks {

using Span = opentelemetry::nostd::shared_ptr<opentelemetry::trace::Span>;
using SpanContext = opentelemetry::trace::SpanContext;

// The tracer options.
struct TracerOptions {
    std::string jaeger_endpoint;
    int jaeger_server_port;
};

/**
 * Handles span creation and provides a compatible interface to `opentelemetry::trace::Tracer`.
 *
 * Spans are organized in a hierarchy. Once a new span is created, through calling `start_trace()`,
 * it will be added as a child to the active span, and replaces its parent as the new active span.
 * When there is no active span, the newly created span is considered as the root span.
 *
 * Here is an example on how to create spans and retrieve traces:
 * ```
 * std::shared_ptr<Tracer> tracer;
 *
 * void f1(std::shared_ptr<Tracer> tracer) {
 *     auto root = tracer->start_trace("root");
 *     sleepFor(Milliseconds(1));
 *     {
 *         auto child = tracer->add_span("child");
 *         sleepFor(Milliseconds(2));
 *     }
 * }
 * ```
 *
 */
class Tracer {
public:
    Tracer(const std::string& service_name, const TracerOptions& tracer_opts = {"localhost", 6381});

    // Shutdown the tracer.
    void shutdown();

    // Creates and returns a new span with `trace_name`
    // this span represents a trace, since it has no parent.
    Span start_trace(const std::string& trace_name);

    // Creates and returns a new span with `span_name` which parent span is `parent_span'.
    Span add_span(const std::string& span_name, const Span& parent_span);

    // Creates and return a new span with `span_name`
    // the span is added to the trace which it's context is `parent_ctx`.
    // parent_ctx contains the required information of the trace.
    Span add_span(const std::string& span_name, const SpanContext& parent_ctx);

private:
    // Init the tracer.
    void init(const std::string& service_name);

    opentelemetry::nostd::shared_ptr<opentelemetry::trace::Tracer> _tracer;
    TracerOptions _tracer_options;
};

} // namespace starrocks
