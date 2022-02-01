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

import java.io.File;
import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmValue;
import nl.mpi.tla.flat.deposit.action.ActionInterface;
import nl.mpi.tla.flat.deposit.util.Saxon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author menzowi
 */
public class Flow {
    
    protected Boolean status = null;

    protected String next = null;
    
    private File base = null;
    
    private static final Logger logger = LoggerFactory.getLogger(Flow.class.getName());
    
    protected XdmNode spec = null;
    
    protected Context context = null;
    
    protected List<Action> noActions = new LinkedList<>();
    
    protected List<Action> initActions = noActions;
    
    protected List<Action> mainActions = noActions;
    
    protected List<Action> exceptionActions = noActions;
    
    protected List<Action> finalActions = noActions;
    
    protected  Map<URI,URI> pids = new LinkedHashMap<>();
    
    protected boolean rollback = false;
    
    protected String start = null;
    
    protected String stop = null;

    public Flow(File spec) throws DepositException {
        this(spec,new HashMap<String,XdmValue>());
    }
    
    public Flow(File spec,Map<String,XdmValue> params) throws DepositException {
        this(new StreamSource(spec),spec,params);
    }
    
    public Flow(Source spec) throws DepositException {
        this(spec,new HashMap<String,XdmValue>());
    }
    public Flow(Source spec,Map<String,XdmValue> params) throws DepositException {
        this(spec,null,params);
    }

    public Flow(Source spec,File base) throws DepositException {
        this(spec,base,new HashMap<String,XdmValue>());
    }

    public Flow(Source spec,File base,Map<String,XdmValue> params) throws DepositException {
        this.base = base;
        try {
            this.spec = Saxon.buildDocument(spec);
        } catch(SaxonApiException e) {
            throw new DepositException(e);
        }
        this.context = new Context(this,this.spec,params);
        loadFlow();
    }
    
    private void loadFlow() throws DepositException {
        try {
            initActions = loadFlow(Saxon.xpath(spec, "/flow/init/action"));
            mainActions = loadFlow(Saxon.xpath(spec, "/flow/main/action"));
            exceptionActions = loadFlow(Saxon.xpath(spec, "/flow/exception/action"));
            finalActions = loadFlow(Saxon.xpath(spec, "/flow/final/action"));
            rollback = Saxon.xpath2boolean(spec, "exists(/flow/rollback)");
        } catch (SaxonApiException ex) {
            throw new DepositException(ex);
        }
    }
    
    private List<Action> loadFlow(XdmValue actions) throws DepositException {
        List<Action> flow = new LinkedList<>();
        for (XdmItem action:actions) {
            try {
                String name = Saxon.xpath2string(action,"@name");
                String clazz = Saxon.xpath2string(action,"@class");
                if (Saxon.hasAttribute(action,"when")) {
                    if (!Saxon.xpath2boolean(action,Saxon.xpath2string(action,"@when"),context.getProperties())) {
                        continue;
                    }
                }
                try {
                    // use the regular class loader to load the action class
                    Class<ActionInterface> face = (Class<ActionInterface>) Class.forName(clazz);
                    // instantiate the class and add it to the workflow
                    ActionInterface actionImpl = face.newInstance();
                    actionImpl.setName(name!=null?name:clazz);
                    flow.add(new Action(actionImpl,Saxon.xpath(action, "parameter")));
                } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
                    Flow.logger.error(" couldn't load action["+name+"]["+clazz+"]! "+e.getMessage());
                    throw new DepositException(e);
                }
            } catch (SaxonApiException ex) {
                Flow.logger.error(" couldn't load actions! "+ex.getMessage());
                throw new DepositException(ex);
            }
        }
        return flow;
    }
    
    public void setStart(String start) {
        this.start = start;
    } 
    
    public String getStart() {
        return this.start;
    }
    
    public void setStop(String stop) {
        this.stop = stop;
    } 
    
    public String getStop() {
        return this.stop;
    }

    public boolean isRerun() {
        return this.start != null;
    }
    
    public Context getContext() {
        return this.context;
    }
    
    public Boolean getStatus() {
        return this.status;
    }
    
    public String getNext() {
        return this.next;
    }
    
    public boolean run() throws DepositException {
        return run(null,null);
    }

    public boolean run(String stop) throws DepositException {
        return run(null,stop);
    }

    public boolean run(String start,String stop) throws DepositException {
        if (start != null)
            this.start = start;
        if (stop != null)
            this.stop = stop;
        DepositException t = null;
        try {
            if (initFlow()) {
            	// code for retreiving pids
            	getSavedpids();
                status = new Boolean(mainFlow(this.start,this.stop));
            } else
                status = new Boolean(false);
        } catch (Exception e) {
            status = new Boolean(false);
            try {
                context.setException(e);
                exceptionFlow(e);
            } catch(DepositException x) {
                t = x;
                Flow.logger.error(" exception during the exception handling flow! "+x.getMessage(),x);
            }
            try {
                if (rollback)
                    rollback();
            } catch(Exception x) {
                Flow.logger.error(" exception during the rollback! "+x.getMessage(),x);
            }
        } finally {
            try {
                finalFlow();
            } catch(DepositException x) {
                t = x;
                Flow.logger.error(" exception during the final flow! "+x.getMessage());
            }
        }
        if (t != null) {
            context.setException(t);
            throw t;
        }
        return status.booleanValue();
    }
    

	private boolean initFlow() throws DepositException {
        Flow.logger.debug("BEGIN  init flow");
        boolean next = true;
        for (Action action:initActions) {
            Flow.logger.debug("ACTION init flow["+action.getName()+"]");
            next = action.perform(context);
            context.save();
            if (!next) {
                Flow.logger.debug("ACTION init BREAK");
                break;
            }
        }
        Flow.logger.debug(" END   init flow["+next+"]");
        return next;
    }
    
    private boolean mainFlow(String start,String stop) throws DepositException {
        Flow.logger.debug("BEGIN  main flow start["+start+"] stop["+stop+"]");
        boolean cont = true;
        boolean run  = (start==null);
        if (next != start)
            Flow.logger.warn("main flow start["+start+"] doesn't match stop/break["+next+"] from previous run");
        for (Action action:mainActions) {
            this.next = action.getName();
            if (!run && start!=null && action.getName().equals(start))
                run = true;
            if (stop!=null && action.getName().equals(stop)) {
                Flow.logger.debug("ACTION main STOP");
                break;
            }
            if (run) {
                Flow.logger.debug("ACTION main flow["+action.getName()+"]");
                cont = action.perform(context);
                context.save();
                if (!cont) {
                    Flow.logger.debug("ACTION main BREAK");
                    break;
                }
            } else
                Flow.logger.debug("ACTION main flow["+action.getName()+"] skipped!");
            this.next = null;
        }
        Flow.logger.debug(" END   main flow["+next+"]");
        return cont;
    }

    private boolean exceptionFlow(Exception e) throws DepositException {
        Flow.logger.debug("BEGIN  exception flow");
        boolean next = true;
        Flow.logger.error(" exception during the init or main flow! "+e.getMessage(),e);
        for (Action action:exceptionActions) {
            Flow.logger.debug("ACTION exception flow["+action.getName()+"]");
            next = action.perform(context);
            context.save();
            if (!next) {
                Flow.logger.debug("ACTION exception BREAK");
                break;
            }
        }
        Flow.logger.debug(" END   exception flow["+next+"]");
        return next;
    }
    
    private void rollback() {
        XdmNode log = context.getRollbackLog();
        for (int a=mainActions.size();a>0;a--) {
            Action action = mainActions.get((a - 1));
            try {
                Map vars = new HashMap();
                vars.put("action",new XdmAtomicValue(action.getName()));
                action.rollback(context,Saxon.xpathList(log, "/rollback/event[@action=$action]",vars));
            } catch (SaxonApiException ex) {
                Flow.logger.error("error during rollback action["+action.getName()+"]",ex);
            }
        }
    }
    
    private void getSavedpids() {
		context.getSave();
	}

    private boolean finalFlow() throws DepositException {
        Flow.logger.debug("BEGIN  final flow");
        boolean next = true;
        for (Action action:finalActions) {
            Flow.logger.debug("ACTION final flow["+action.getName()+"]");
            next = action.perform(context);
            context.save();
            if (!next) {
                Flow.logger.debug("ACTION final BREAK");
                break;
            }
        }
        Flow.logger.debug(" END   final flow["+next+"]");
        return next;
    }
    
    class Action {
        
        private ActionInterface action = null;
        private XdmValue params = null;
        
        public Action(ActionInterface action,XdmValue params) {
            this.action = action;
            this.params = params;
        }
        
        public String getName() {
            if (this.action==null)
                return null;
            return this.action.getName();
        }
        
        public boolean perform(Context context) throws DepositException {
            if (this.action==null)
                throw new DepositException("Action is unknown!");
            if (this.params==null)
                throw new DepositException("Action["+this.action.getName()+"] parameters are unknown!");
            try {
                this.action.setParameters(context.loadParameters(new LinkedHashMap<String,XdmValue>(),this.params,"parameter"));
            } catch (SaxonApiException e) {
                throw new DepositException("JIT loading and expanding parameters for action["+this.action.getName()+"] failed!",e);
            }
            return this.action.perform(context);
        }
        
        public void rollback(Context context,List<XdmItem> events) {
            Flow.logger.debug("rollback action["+this.action.getName()+"]["+this.action.getClass().getName()+"]");
            for (ListIterator<XdmItem> iter = events.listIterator(events.size());iter.hasPrevious();) {
                try {
                    XdmItem x = iter.previous();
                    Flow.logger.debug("- rollback event["+Saxon.xpath2string(x,"@type")+"]");
                    for (XdmItem p:Saxon.xpath(x, "./param"))
                        Flow.logger.debug("- - param["+Saxon.xpath2string(p, "@name")+"="+Saxon.xpath2string(p, "@value")+"]");
                } catch (SaxonApiException ex) {
                }
            }
            this.action.rollback(context, events);
        }
    }

}