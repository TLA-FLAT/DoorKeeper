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
package nl.mpi.tla.flat.deposit.context;

import java.util.Map;
import java.util.Properties;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmValue;

/**
 *
 * @author menzowi
 */
public class SystemProperties implements ImportPropertiesInterface {

    @Override
    public void importProperties(String prefix,Map<String, XdmValue> props) {
        String pre = (prefix==null?"":prefix);
        Properties sprops = System.getProperties();
        for (Object name : sprops.keySet()) {
            String key = pre+name.toString();
            // These names become XPath variable QNames when the flow config's
            // <property> elements are evaluated. A ':' in the name produces an
            // invalid local name, which aborts the whole property load — and
            // therefore every deposit — the moment any library sets a system
            // property with a colon in its name at runtime. Such a property
            // can't be a usable variable anyway, so skip it instead of failing.
            if (key.indexOf(':') >= 0)
                continue;
            props.put(key,new XdmAtomicValue(sprops.get(name).toString().replaceAll("\\{", "{{").replaceAll("\\}","}}")));
        }
    }
    
}
