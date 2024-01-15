/* 
 * Copyright (C) 2015-2017 The Language Archive
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package nl.mpi.tla.flat.deposit.action;

import nl.mpi.tla.flat.deposit.Context;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import java.util.Iterator;

/**
 *
 * @author menzowi, pautri
 */
public class WorkspaceLogCleanup extends AbstractAction {

    private static final LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
    private static final Logger logger = ctx.getLogger("nl.mpi.tla.flat.deposit");

    @Override
    public boolean perform(Context context) {

        for (Iterator<Appender<ILoggingEvent>> index = logger.iteratorForAppenders(); index.hasNext();) {
            Appender<ILoggingEvent> appender = index.next();
            if (appender.getName().equals("USER") || appender.getName().equals("DEVEL")) {
                appender.stop();
            }
        }

        MDC.remove("sip");
        return true;
    }

}
