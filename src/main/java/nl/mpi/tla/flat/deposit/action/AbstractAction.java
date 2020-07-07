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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmValue;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;

/**
 *
 * @author menzowi
 */
abstract public class AbstractAction implements ActionInterface {
    
    // action name
    
    protected String name = null;

    @Override
    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        if (this.name!=null && !this.name.trim().equals(""))
            return this.name;
        return this.getClass().getName();
    }
    
    // parameters

    protected Map<String, XdmValue> params = new LinkedHashMap<String, XdmValue>();

    @Override
    public void setParameters(Map<String, XdmValue> params) {
        this.params = params;
    }
    
    public boolean hasParameter(String name) {
        return params.containsKey(name);
    }

    public String getParameter(String name,String def) {
        if (hasParameter(name))
            return params.get(name).toString();
        return def;
    }

    public String getParameter(String name) throws DepositException {
        if (hasParameter(name))
            return params.get(name).toString();
        throw new DepositException("Mandatory parameter["+name+"] for Action["+this.getName()+"] is not available!");
    }
    
    // overwrite properties
    
    private Properties props = null;
    
    public Properties getOverwriteProperties(Context context) {
        if (props == null) {
            props = new Properties();
            String overwrite = this.getParameter("overwrite", context.getProperty("overwrite", "").toString());
            if (!overwrite.equals("")) {
                try {
                    File pf = new File(overwrite);
                    if (pf.exists() && pf.canRead()) {
                        if (overwrite.endsWith(".xml"))
                            props.loadFromXML(new FileInputStream(pf));
                        else
                            props.load(new FileInputStream(pf));
                    }
                } catch (Exception ex) {
                    // ignore
                }
            }
        }
        return props;
    }
            
    // abstract
    
    @Override
    abstract public boolean perform(Context context) throws DepositException;
    
    @Override
    public void rollback(Context context,List<XdmItem> events) {
    }
    
}
