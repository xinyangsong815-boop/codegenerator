package cn.bank.devtool.regression;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.Marker;

public final class HttpPrivacyLogFilter extends TurboFilter {
    public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] parameters, Throwable t) {
        return logger.getName().startsWith("org.apache.http") && level.toInt() < 30000 ? FilterReply.DENY : FilterReply.NEUTRAL;
    }
}
