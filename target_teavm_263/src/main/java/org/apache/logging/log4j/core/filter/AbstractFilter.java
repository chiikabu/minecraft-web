// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.apache.logging.log4j.core.filter;

import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;

public abstract class AbstractFilter implements Filter {
    protected AbstractFilter() { }
    public Result filter(LogEvent event) { return Result.NEUTRAL; }
}
