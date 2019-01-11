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
package nl.mpi.tla.flat.deposit.util;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import static nl.mpi.tla.flat.deposit.sip.cmdi.CMD.CMD_NS;
import static nl.mpi.tla.flat.deposit.sip.cmdi.CMD.LAT_NS;

/**
 *
 * @author menzowi
 */
public class Global {
    final static protected SimpleDateFormat ASOF = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS");
    final static public Map<String,String> NAMESPACES = new LinkedHashMap<>();
    
    static {
        NAMESPACES.put("cmd", CMD_NS);
        NAMESPACES.put("lat", LAT_NS);
        NAMESPACES.put("dc", "http://purl.org/dc/elements/1.1/");
        NAMESPACES.put("fits", "http://hul.harvard.edu/ois/xml/ns/fits/fits_output");
        NAMESPACES.put("flat", "java:nl.mpi.tla.flat");
        NAMESPACES.put("foxml", "info:fedora/fedora-system:def/foxml#");
        NAMESPACES.put("sx", "java:nl.mpi.tla.saxon");
        NAMESPACES.put("xs", "http://www.w3.org/2001/XMLSchema");
    };
    
    static public String asOfDateTime(Date date) {
        return ASOF.format(date)+"Z";
    }

    static public Date asOfDateTime(String date) throws ParseException {
        return ASOF.parse(date.replaceFirst("Z$",""));
    }
}
