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
package nl.mpi.tla.flat.deposit;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;

import nl.mpi.tla.flat.deposit.sip.SIPInterface;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmValue;
import nl.mpi.tla.flat.deposit.action.ActionInterface;
import nl.mpi.tla.flat.deposit.context.ImportPropertiesInterface;
import nl.mpi.tla.flat.deposit.util.Global;
import nl.mpi.tla.flat.deposit.util.Saxon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.supercsv.io.CsvListReader;
import org.supercsv.io.CsvListWriter;
import org.supercsv.io.ICsvListReader;
import org.supercsv.io.ICsvListWriter;
import org.supercsv.prefs.CsvPreference;

import com.opencsv.CSVReader;
import com.opencsv.CSVWriter;
import com.opencsv.exceptions.CsvException;
import java.nio.file.Path;

/**
 *
 * @author menzowi
 * @author pavsri
 */
public class Context {

	private static final Logger logger = LoggerFactory.getLogger(Context.class.getName());

	protected Logger actionLogger = logger;
	protected Marker marker = null;

	protected Flow flow = null;

	protected Map<String, XdmValue> props = new LinkedHashMap<>();

	protected Map<URI, URI> pids = new LinkedHashMap<>();

	protected SIPInterface sip = null;

	protected Exception ex = null;

	protected PrintWriter rollbackLog = null;

	protected Map<String, Object> memory = new LinkedHashMap<>();

	FileWriter fileWriter;
	FileReader fileReader;

	// constructor

	public Context(Flow flow, XdmNode spec, Map<String, XdmValue> params) throws DepositException {
		this.flow = flow;
		props.putAll(params);
		loadNamespaces(spec);
		loadProperties(spec);
		getSave();
	}

	// Flow

	public Flow getFlow() {
		return flow;
	}

	// Namespaces
	private void loadNamespaces(XdmNode spec) throws DepositException {
		try {
			for (XdmItem ns : Saxon.xpath(spec, "/flow/config/namespace"))
				Global.NAMESPACES.put(Saxon.xpath2string(ns, "@prefix"), Saxon.xpath2string(ns, "@uri"));
		} catch (SaxonApiException e) {
			throw new DepositException(e);
		}
	}

	// Properties

	private void loadProperties(XdmNode spec) throws DepositException {
		try {
			importProperties(spec);
			loadParameters(props, Saxon.xpath(spec, "/flow/config/property", props), "property");
		} catch (SaxonApiException e) {
			throw new DepositException(e);
		}
	}

	private void importProperties(XdmNode spec) throws SaxonApiException, DepositException {
		for (XdmItem imp : Saxon.xpath(spec, "/flow/config/import", props)) {
			String prefix = Saxon.xpath2string(imp, "@prefix");
			String clazz = Saxon.xpath2string(imp, "@class");
			try {
				Class<ImportPropertiesInterface> face = (Class<ImportPropertiesInterface>) Class.forName(clazz);
				ImportPropertiesInterface importer = face.newInstance();
				importer.importProperties(prefix, props);
			} catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
				this.logger.error(" couldn't load property importer[" + clazz + "][" + prefix + "]! " + e.getMessage());
				throw new DepositException(e);
			}
		}
	}

	public Map<String, XdmValue> getProperties() {
		return props;
	}

	public boolean hasProperty(String name) {
		return props.containsKey(name);
	}

	public XdmValue getProperty(String name, String def) {
		if (hasProperty(name))
			return props.get(name);
		return (new XdmAtomicValue(def));
	}

	public void setProperty(String name, XdmValue val) {
		props.put(name, val);
	}

	// SIP

	public boolean hasSIP() {
		return (this.sip != null);
	}

	public void setSIP(SIPInterface sip) throws DepositException {
		if (this.sip != null)
			throw new DepositException("SIP is already specified!");
		this.sip = sip;
	}

	public SIPInterface getSIP() throws DepositException {
		if (this.sip == null)
			throw new DepositException("SIP is not specified!");
		return this.sip;
	}

	// Save (the SIP)

	public boolean save() throws DepositException {
		saveEvent();
		return (this.hasSIP() ? this.sip.save() : false);
	}

	// PIDs

	public URI addPID(URI pid, URI red) {
		return pids.put(pid, red);
	}

	public void delPID(URI pid) {
		if (pids.containsKey(pid)) {
			pids.remove(pid);
		}
	}

	public boolean hasPID(URI pid) {
		return pids.containsKey(pid);
	}

	public URI getPID(URI pid) {
		return pids.get(pid);
	}

	public Map<URI, URI> getPIDs() {
		return pids;
	}

	// Memory

	public Object putInMemory(String key, Object val) {
		logger.debug("put memory key[" + key + "][" + val + "]");
		return memory.put(key, val);
	}

	public boolean hasInMemory(String key) {
		for (String k : memory.keySet())
			logger.debug("has memory key[" + k + "]");
		logger.debug("has memory key[" + key + "][" + memory.containsKey(key) + "]");
		return memory.containsKey(key);
	}

	public Object getFromMemory(String key) {
		logger.debug("get memory key[" + key + "][" + memory.get(key) + "]");
		return memory.get(key);
	}

	// Exception

	public void setException(Exception ex) {
		this.ex = ex;
	}

	public boolean hasException() {
		return (this.ex != null);
	}

	// saving PIDs

	public Exception getException() {
		return this.ex;
	}

	public void saveEvent() {
		String file = null;
		fileWriter = null;
		try {
			file = getPidFile();
			if (file != null) {
				File f = new File(file);
				if (!f.exists())
					f.createNewFile();
				f.setWritable(true, false);
				fileWriter = new FileWriter(file);
			}
		} catch (IOException ex) {
			this.logger.error("Couldn't create/open save pids csv file[" + file + "]", ex);
			System.exit(1);
		}
		if (fileWriter != null) {
			try {
				if (!pids.isEmpty()) {
					ICsvListWriter saveLog = new CsvListWriter(fileWriter, CsvPreference.STANDARD_PREFERENCE);
					for (Map.Entry<URI, URI> entry : pids.entrySet()) {
						saveLog.write(entry.getKey().toString(), entry.getValue().toString());
						logger.debug("saved["+entry.getKey().toString()+","+entry.getValue().toString()+"]");
					}
					saveLog.close();
				}
				else {
					logger.info("Hashmap of pids is empty. Nothing to store into the file!");
				}
			} catch (IOException e) {
				this.logger.error("Couldn't write pids to csv file["+ this.getProperty("dk-pidList", "pids.csv").toString() + "]", e);
				System.exit(1);
			}
		} else
			this.logger.error("No pids saved for this run- saveEvent()!");
	}

	public String getPidFile() {
		String filename = null;
		this.logger.info("XdmValue= " + this.getProperty("dk-pidList", "pids.csv"));
		if (this.getProperty("dk-pidList", "pids.csv") != null) {
			XdmValue pidFileProperty = this.getProperty("dk-pidList", "pids.csv");
			filename = "./"+pidFileProperty.toString();
		} else {
			this.logger.info("There is no pids saved! pids.csv is not present!");
		}
		return filename;
	}

	public void getSave() {
		String file = null;
		fileReader = null;
		try {
			file = getPidFile();
			if (file != null) {
				File f = new File(file);
				if (f.exists() && f.length() != 0) 
					fileReader = new FileReader(file);
				else
					logger.info("pids.csv file does not exist in the path!");
			}
		} catch (IOException ex) {
			this.logger.error("Couldn't create/open save pids csv file[" + file + "]", ex);
			System.exit(1);
		}
		if (fileReader != null) {
			try {
				CSVReader readLog = new CSVReader(fileReader);
				Map<URI, URI> tempPids = new LinkedHashMap<>();
				List<String[]> readingData = readLog.readAll();
				for (String[] list : readingData) {
					tempPids.put(URI.create(list[0]), URI.create(list[1]));
				}
				pids = tempPids;
			} catch (IOException | CsvException ex) {
				this.logger.error("Couldn't read save log file[" + this.getProperty("dk-pidList", "pids.csv").toString() + "]",ex);
				System.exit(1);
			}
		} else
			this.logger.info("No pids saved for this run!- getSave()");
	}

	// Rollback

	protected void initRollbackLog() {
		if (rollbackLog == null) {
			try {
				rollbackLog = new PrintWriter(
						new FileWriter(this.getProperty("dk-rollbackLog", "rollback.log").toString(), true), true);
			} catch (IOException ex) {
				this.logger.error("Couldn't create/open rollback log file["
						+ this.getProperty("dk-rollbackLog", "rollback.log").toString() + "]", ex);
				System.exit(1);
			}
		}
	}

	protected String escXML(String s) {
		return s.replaceAll("&", "&amp;").replaceAll("\"", "&quot;").replaceAll("'", "&apos;").replaceAll("<", "&lt;")
				.replaceAll(">", "&gt;");
	}

	public void registerRollbackEvent(ActionInterface action, String event, String... params) {
		initRollbackLog();
		if (params.length % 2 != 0) {
			this.logger.warn("uneven param list for action[" + action.getName() + "] event[" + event + "]!");
		}
		rollbackLog.format("<event action=\"%s\" type=\"%s\">", escXML(action.getName()), escXML(event));
		for (int p = 0; p < params.length; p++) {
			if ((p + 1) < params.length)
				rollbackLog.format("<param name=\"%s\" value=\"%s\"/>", escXML(params[p]), escXML(params[++p]));
			else
				rollbackLog.format("<param name=\"%s\"/>", escXML(params[p]));
		}
		rollbackLog.format("</event>");
		rollbackLog.flush();
	}

	public XdmNode getRollbackLog() {
		try {
			initRollbackLog();
			rollbackLog.flush();
			rollbackLog.close();
			rollbackLog = null; // force reopening if needed
			String xml = "<?xml version=\"1.0\" ?>\n" + "<!DOCTYPE rollback [<!ENTITY data SYSTEM \"rollback.log\">]>\n"
					+ "<rollback>&data;</rollback>";
			xml = xml.replaceAll("rollback.log", this.getProperty("dk-rollbackLog", "rollback.log").toString());
			return Saxon.buildDocument(new StreamSource(new StringReader(xml)));
		} catch (SaxonApiException ex) {
			this.logger.error("Couldn't read rollback log file["
					+ this.getProperty("dk-rollbackLog", "rollback.log").toString() + "]", ex);
			System.exit(1);
		}
		return null;
	}

	// Utilities: general method to load properties or parameters

	public Map<String, XdmValue> loadParameters(Map<String, XdmValue> map, XdmValue params, String type)
			throws SaxonApiException, DepositException {
		for (XdmItem param : params) {
			String name = Saxon.xpath2string(param, "@name");
			if (Saxon.hasAttribute(param, "when")) {
				if (!Saxon.xpath2boolean(param, Saxon.xpath2string(param, "@when"), props)) {
					continue;
				}
			}
			if (Saxon.xpath2boolean(param, "../" + type + "[@name='" + name + "']/@uniq='true'")) {
				if (map.containsKey(name)) {
					this.logger.error(type + "[" + name + "] should be unique!");
					throw new DepositException(type + "[" + name + "] should be unique!");
				}
			}
			if (Saxon.hasAttribute(param, "value")) {
				String avt = Saxon.avt(Saxon.xpath2string(param, "@value"), param, props, Global.NAMESPACES);
				XdmValue val = new XdmAtomicValue(avt);
				if (map.containsKey(name))
					map.put(name, map.get(name).append(val));
				else
					map.put(name, val);
			} else if (Saxon.hasAttribute(param, "xpath")) {
				try {
					XdmValue val = Saxon.xpath(param, Saxon.xpath2string(param, "@xpath"), props, Global.NAMESPACES);
					if (map.containsKey(name))
						map.put(name, map.get(name).append(val));
					else
						map.put(name, val);
				} catch (SaxonApiException e) {
					this.logger.error(type + "[" + name + "] xpath[" + Saxon.xpath2string(param, "@xpath")
							+ "] couldn't be evaluated! " + e.getMessage());
					throw new DepositException(e);
				}
			}
			int i = 1;
			for (XdmItem val : map.get(name))
				this.logger.debug(type + "[" + name + "][" + (i++) + "/" + map.get(name).size() + "]["
						+ val.getStringValue() + "]");
		}
		boolean closure = true;
		int c = 0;
		do {
			c++;
			closure = true;
			for (String name : map.keySet()) {
				XdmValue vals = map.get(name);
				XdmValue nvals = null;
				for (XdmItem val : vals) {
					String avt = Saxon.avt(val.toString(), val, props, Global.NAMESPACES, false);
					if (!val.toString().equals(avt))
						closure = false;
					if (nvals == null)
						nvals = new XdmAtomicValue(avt);
					else
						nvals = nvals.append(new XdmAtomicValue(avt));
				}
				map.put(name, nvals);
				this.logger.debug("closure[" + c + "] " + type + "[" + name + "][" + map.get(name) + "]");
			}
		} while (!closure);
		for (String name : map.keySet()) {
			XdmValue vals = map.get(name);
			XdmValue nvals = null;
			for (XdmItem val : vals) {
				String v = val.toString().replaceAll("\\{\\{", "{").replaceAll("\\}\\}", "}");
				if (nvals == null)
					nvals = new XdmAtomicValue(v);
				else
					nvals = nvals.append(new XdmAtomicValue(v));
			}
			map.put(name, nvals);
		}
		return map;
	}

}
