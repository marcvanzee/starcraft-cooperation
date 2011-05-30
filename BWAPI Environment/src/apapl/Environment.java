package apapl;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Vector;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import apapl.data.APLFunction;
import apapl.data.Term;
import eis.AgentListener;
import eis.EnvironmentInterfaceStandard;
import eis.EnvironmentListener;
import eis.exceptions.ActException;
import eis.exceptions.AgentException;
import eis.exceptions.EntityException;
import eis.exceptions.EnvironmentInterfaceException;
import eis.exceptions.ManagementException;
import eis.exceptions.NoEnvironmentException;
import eis.exceptions.PerceiveException;
import eis.exceptions.RelationException;
import eis.iilang.Action;
import eis.iilang.EnvironmentCommand;
import eis.iilang.EnvironmentEvent;
import eis.iilang.Parameter;
import eis.iilang.Percept;

/**
 * This class is the superclass for original 2APL environments. It implements
 * the basic functionality to define custom made environments in which the
 * agents can perform external actions.
 * <p />
 * After 2APL has migrated to the EIS (Environment Interface Standard), this
 * class serves as a wrapper that makes its subclasses to comply to EIS.
 * <p />
 * EIS distinguishes between agents and entities. Original 2APL environments did
 * not employ this distinction. Therefore, this wrapper present EIS entities to
 * 2APL environments as agents. It also enforces one-to-one relation between EIS
 * agents and entities to fit to concept of 2APL environment.
 * <p />
 * Further note that a module reports itself to the environment as an individual
 * agent. It is up to the concrete environment implementation to decide whether
 * modules of one agent will be presented separately or as one entity.
 * <p />
 * Extending this class is now discouraged as 2APL platform has migrated to the
 * Environment Interface Standard. It solely exists to maintain
 * backwards-compatibility.
 * 
 */
public class Environment implements EnvironmentInterfaceStandard {

    /**
     * This method is meant to be overriden by sub-classes. It is invoked when
     * an agent enters the environment. Note that this method is also invoked each
     * time an agent is re-compiled.
     * 
     * @param name the local name of the specific agent.
     */
    protected void addAgent(String name) {
    };

    /**
     * This method is invoked when an agent operating in this environment is
     * removed. Note that this method is also invoked when an agent is
     * recompiled.
     * 
     * @param name the local name of the specific agent.
     */
    protected void removeAgent(String name) {
    };

    /**
     * Throws an event to one or more agents. The EIS wrapper converts the event
     * to a percept generated by entities representing the agents.
     * 
     * @param event the event to be thrown
     * @param receivers the agent listed to receive the event. If no
     *        no agents are listed, the event will be thrown to all agents.
     */
    protected final void throwEvent(APLFunction e, String... receivers) {

        String[] receivingEntities;
       
        if (receivers.length == 0) {
            // Event is sent to all entities
            receivingEntities = getEntities().toArray(new String[0]);
        }
        else {            
            receivingEntities = receivers;
        }           
        
        Percept percept = IILConverter.convertToPercept(e);
        try {
            notifyAgentsViaEntity(percept, receivingEntities);
        } catch (EnvironmentInterfaceException e1) {
            e1.printStackTrace();
        }
    }

    /**
     * Returns the name of this environment. This is equal to the package name.
     */
    public final String getName() {
        String sourceEnv = getClass().getName();
        return sourceEnv.substring(0, sourceEnv.lastIndexOf("."));
    }

    /**
     * Invoked whenever the MAS is closed. This method can be used to clean up
     * resources when the environment is about to be deinstantiated.
     */
    public void takeDown() {
    }

    /**
     * Creates a new EIS entity corresponding to a 2APL environment agent.
     * Associates the EIS entity with the EIS agent of the same name. This
     * function enforces the one to one - EIS agent to EIS entity - relation,
     * which is needed to fit the framework used in 2APL environments.
     * 
     * @param agent both name of the EIS entity to be added and the name of EIS
     *        agent to associate the entity with
     */
    public void addAgentEntity(String agent) {
        try {
            addEntity(agent);
        } catch (EntityException e) {
            return;
        }

        try {
            associateEntity(agent, agent);
        } catch (RelationException e) {
            return;
        }
    }

    /**
     * Deletes EIS entity corresponding to EIS agent. 
     * 
     * @param agent the name of EIS entity to be removed  
     */
    public void removeAgentEntity(String agent) {                
        try {
            freeEntity(agent);
        } catch (RelationException e) {            
            return;
        }
        
        try {
            deleteEntity(agent);
        } catch (EntityException e) {            
            return;
        }
    }

    /*
     * Implementation of Environment Interface Standard v.02
     */

    /**
     * This is a list of registered agents.
     * <p/>
     * Only registered agents can act and be associated with entities.
     */
    private LinkedList<String> registeredAgents = null;

    /**
     * This is a list of entities.
     */
    private LinkedList<String> entities = null;

    /**
     * This is a list of entities, that are not associated with any agent.
     */
    private LinkedList<String> freeEntities = null;

    /**
     * This map stores the agents-entities-relation.
     */
    private ConcurrentHashMap<String, HashSet<String>> agentsToEntities = null;

    /**
     * This collection stores the listeners that are used to notify about
     * certain events.
     * <p/>
     * The collection can be changed by invoking the respective methods for
     * attaching and detaching listeners.
     */
    private Vector<EnvironmentListener> environmentListeners = null;

    /**
     * Stores for each agent (represented by a string) a set of listeners.
     */
    private ConcurrentHashMap<String, HashSet<AgentListener>> agentsToAgentListeners = null;

    /**
     * Instantiates the class.
     */
    public Environment() {

        environmentListeners = new Vector<EnvironmentListener>();
        agentsToAgentListeners = new ConcurrentHashMap<String, HashSet<AgentListener>>();

        registeredAgents = new LinkedList<String>();
        entities = new LinkedList<String>();
        freeEntities = new LinkedList<String>();
        agentsToEntities = new ConcurrentHashMap<String, HashSet<String>>();
    }

    /*
     * Listener functionality. Attaching, detaching, notifying listeners.
     */

    /*
     * (non-Javadoc)
     * 
     * @see eis.NewInterface#attachEnvironmentListener(eis.EnvironmentListener)
     */
    public final void attachEnvironmentListener(EnvironmentListener listener) {
        if (environmentListeners.contains(listener) == false)
            environmentListeners.add(listener);
    }

    /*
     * (non-Javadoc)
     * 
     * @see eis.NewInterface#detachEnvironmentListener(eis.EnvironmentListener)
     */
    public final void detachEnvironmentListener(EnvironmentListener listener) {
        if (environmentListeners.contains(listener) == true)
            environmentListeners.remove(listener);
    }

    /*
     * (non-Javadoc)
     * 
     * @see eis.NewInterface#attachAgentListener(java.lang.String,
     * eis.AgentListener)
     */
    public final void attachAgentListener(String agent, AgentListener listener) {
        if (registeredAgents.contains(agent) == false)
            return;

        HashSet<AgentListener> listeners = agentsToAgentListeners.get(agent);

        if (listeners == null)
            listeners = new HashSet<AgentListener>();

        listeners.add(listener);

        agentsToAgentListeners.put(agent, listeners);
    }

    /*
     * (non-Javadoc)
     * 
     * @see eis.NewInterface#detachAgentListener(java.lang.String,
     * eis.AgentListener)
     */
    public final void detachAgentListener(String agent, AgentListener listener) {
        if (registeredAgents.contains(agent) == false)
            return;

        HashSet<AgentListener> listeners = agentsToAgentListeners.get(agent);

        if (listeners == null || listeners.contains(agent) == false)
            return;

        listeners.remove(listener);

        agentsToAgentListeners.put(agent, listeners);
    }

    /**
     * Notifies agents about a percept.
     * 
     * @param percept is the percept
     * @param agents is the array of agents that are to be notified about the
     *        event. If the array is empty, all registered agents will be
     *        notified. The array has to contain only registered agents.
     * @throws AgentException is thrown if at least one of the agents in the
     *         array is not registered.
     */
    protected final void notifyAgents(Percept percept, String... agents)
            throws EnvironmentInterfaceException {

        // FIXME Is this really intended?
        
        /* // no listeners, no notification
        if (environmentListeners.isEmpty())
            return;
        */

        // send to all registered agents
        if (agents == null) {
            for (String agent : registeredAgents) {

                HashSet<AgentListener> agentListeners = agentsToAgentListeners
                        .get(agent);

                if (agentListeners == null)
                    continue;

                for (AgentListener listener : agentListeners) {
                    listener.handlePercept(agent, percept);
                }
            }
            return;
        }

        // send to specified agents
        for (String agent : agents) {

            if (!registeredAgents.contains(agent))
                throw new EnvironmentInterfaceException("Agent " + agent
                        + " has not registered to the environment.");

            HashSet<AgentListener> agentListeners = agentsToAgentListeners
                    .get(agent);

            if (agentListeners == null)
                continue;

            for (AgentListener listener : agentListeners) {
                listener.handlePercept(agent, percept);
            }
        }
    }

    /**
     * Sends a percept to an agent/several agents via a given array of entities.
     * 
     * @param percept
     * @param entity
     * @throws EnvironmentInterfaceException
     */
    protected final void notifyAgentsViaEntity(Percept percept,
            String... pEntities) throws EnvironmentInterfaceException {

        // check
        for (String entity : pEntities)
            if (this.entities.contains(entity) == false)
                throw new EnvironmentInterfaceException("\"" + entity
                        + "\" does not exist.");

        // use all entities
        if (pEntities.length == 0) {

            for (String entity : entities) {
                for (Entry<String, HashSet<String>> entry : agentsToEntities
                        .entrySet()) {

                    if (entry.getValue().contains(entity))
                        this.notifyAgents(percept, entry.getKey());

                }
            }
        }
        // use given array
        else {

            for (String entity : pEntities) {
                for (Entry<String, HashSet<String>> entry : agentsToEntities
                        .entrySet()) {

                    if (entry.getValue().contains(entity))
                        this.notifyAgents(percept, entry.getKey());

                }
            }
        }
    }

    /**
     * Notifies all listeners about an entity that is free.
     * 
     * @param entity is the free entity.
     */
    protected final void notifyFreeEntity(String entity) {
        for (EnvironmentListener listener : environmentListeners) {
            listener.handleFreeEntity(entity);
        }
    }

    /**
     * Notifies all listeners about an entity that has been newly created.
     * 
     * @param entity is the new entity.
     */
    protected final void notifyNewEntity(String entity) {
        for (EnvironmentListener listener : environmentListeners) {
            listener.handleNewEntity(entity);
        }
    }

    /**
     * Notifies all listeners about an entity that has been deleted.
     * 
     * @param entity is the deleted entity.
     */
    protected final void notifyDeletedEntity(String entity) {
        for (EnvironmentListener listener : environmentListeners) {
            listener.handleDeletedEntity(entity);
        }
    }

    /**
     * Notifies the listeners about an environment-event.
     * 
     * @param event
     */
    protected final void notifyEnvironmentEvent(EnvironmentEvent event) {
        for (EnvironmentListener listener : environmentListeners) {
            listener.handleEnvironmentEvent(event);
        }
    }

    /*
     * Registering functionality. Registering and unregistering agents.
     */

    /*
     * (non-Javadoc)
     * 
     * @see eis.NewInterface#registerAgent(java.lang.String)
     */
    public final void registerAgent(String agent) throws AgentException {
        if (registeredAgents.contains(agent))
            throw new AgentException("Agent " + agent
                    + " has already registered to the environment.");

        registeredAgents.add(agent);
    }

    /*
     * (non-Javadoc)
     * 
     * @see eis.NewInterface#unregisterAgent(java.lang.String)
     */
    public final void unregisterAgent(String agent) throws AgentException {
        // fail if agents is not registered
        if (!registeredAgents.contains(agent))
            throw new AgentException("Agent " + agent
                    + " has not registered to the environment.");

        // remove from mapping, might be null
        agentsToEntities.remove(agent);

        // finally remove from registered list
        registeredAgents.remove(agent);
    }

    /*
     * Entity functionality. Adding and removing entities.
     */

    /*
     * (non-Javadoc)
     * 
     * @see eis.NewInterface#getAgents()
     */
    @SuppressWarnings("unchecked")
    public final LinkedList<String> getAgents() {
        return (LinkedList<String>) registeredAgents.clone();
    }

    /**
     * Adds an entity to the environment.
     * 
     * @param entity is the identifier of the entity that is to be added.
     * @throws PlatformException is thrown if the entity already exists.
     */
    protected final void addEntity(String entity) throws EntityException {
        // fail if entity does exist
        if (entities.contains(entity))
            throw new EntityException("Entity \"" + entity
                    + "\" does already exist");

        // Adding an entity to EIS corresponds to adding an agent in a 2APL
        // environment
        addAgent(entity);

        // add
        entities.add(entity);
        freeEntities.add(entity);
    }

    /**
     * Deletes an entity, by removing its id from the internal list, and
     * disassociating it from the respective agent.
     * 
     * @param entity the id of the entity that is to be removed.
     * @throws PlatformException if the agent does not exist.
     */
    // TODO use freeEntity here
    protected final void deleteEntity(String entity) throws EntityException {
        // fail if entity does not exist
        if (!entities.contains(entity))
            throw new EntityException("Entity \"" + entity
                    + "\" does not exist");

        // find the association and remove
        for (Entry<String, HashSet<String>> entry : agentsToEntities.entrySet()) {

            String agent = entry.getKey();
            HashSet<String> ens = entry.getValue();

            if (ens.contains(entity)) {

                ens.remove(entity);

                agentsToEntities.put(agent, ens);

                break;
            }
        }

        // Removing an entity in EIS corresponds to removing agent in a 2APL
        // environment
        removeAgent(entity);

        // finally delete
        entities.remove(entity);
        freeEntities.remove(entity);
    }

    /*
     * (non-Javadoc)
     * 
     * @see eis.NewInterface#getEntities()
     */
    @SuppressWarnings("unchecked")
    public final LinkedList<String> getEntities() {
        return (LinkedList<String>) entities.clone();
    }

    /*
     * Agents-entity-relation manipulation functionality.
     */

    /*
     * (non-Javadoc)
     * 
     * @see eis.NewInterface#associateEntity(java.lang.String, java.lang.String)
     */
    public void associateEntity(String agent, String entity)
            throws RelationException {

        // check if exists
        if (!entities.contains(entity))
            throw new RelationException("Entity \"" + entity
                    + "\" does not exist!");

        if (!registeredAgents.contains(agent))
            throw new RelationException("Agent \"" + entity
                    + "\" has not been registered!");

        // check if associated
        if (!freeEntities.contains(entity))
            throw new RelationException("Entity \"" + entity
                    + "\" has already been associated!");

        // remove
        freeEntities.remove(entity);

        // associate
        HashSet<String> ens = agentsToEntities.get(agent);
        if (ens == null) {
            ens = new HashSet<String>();
        }
        ens.add(entity);
        agentsToEntities.put(agent, ens);
    }

    /*
     * (non-Javadoc)
     * 
     * @see eis.NewInterface#freeEntity(java.lang.String)
     */
    public final void freeEntity(String entity) throws RelationException {

        // check if exists
        if (!entities.contains(entity))
            throw new RelationException("Entity \"" + entity
                    + "\" does not exist!");

        // find the association and remove
        boolean associated = false;
        for (Entry<String, HashSet<String>> entry : agentsToEntities.entrySet()) {
            String agent = entry.getKey();
            HashSet<String> ens = entry.getValue();

            if (ens.contains(entity)) {
                ens.remove(entity);
                agentsToEntities.put(agent, ens);
                associated = true;
                break;
            }
        }

        // fail if entity has not been associated
        if (associated == false)
            throw new RelationException("Entity \"" + entity
                    + "\" has not been associated!");

        // add to free entites
        freeEntities.add(entity);
    }

    /*
     * (non-Javadoc)
     * 
     * @see eis.NewInterface#freeAgent(java.lang.String)
     */
    public final void freeAgent(String agent) throws RelationException {

        // check if exists
        if (!registeredAgents.contains(agent))
            throw new RelationException("Agent \"" + agent
                    + "\" does not exist!");

        HashSet<String> ens = agentsToEntities.get(agent);

        this.freeEntities.addAll(ens);

        // TODO use listeners

        agentsToEntities.remove(agent);
    }

    /*
     * (non-Javadoc)
     * 
     * @see eis.NewInterface#freePair(java.lang.String, java.lang.String)
     */
    public final void freePair(String agent, String entity)
            throws RelationException {

        // check if exists
        if (!registeredAgents.contains(agent))
            throw new RelationException("Agent \"" + agent
                    + "\" does not exist!");

        // check if exists
        if (!entities.contains(entity))
            throw new RelationException("Entity \"" + entity
                    + "\" does not exist!");

        HashSet<String> ens = agentsToEntities.get(agent);

        if (ens == null || ens.contains(entity) == false)
            throw new RelationException("Agent \"" + agent
                    + " is not associated with entity \"" + entity + "\"!");

        // update mapping
        ens.remove(entity);
        agentsToEntities.put(agent, ens);

        // store as free entity
        this.freeEntities.add(entity);

        // TODO use listeners
    }

    /*
     * (non-Javadoc)
     * 
     * @see eis.NewInterface#getAssociatedEntities(java.lang.String)
     */
    public final HashSet<String> getAssociatedEntities(String agent)
            throws AgentException {

        if (registeredAgents.contains(agent) == false)
            throw new AgentException("Agent \"" + agent
                    + "\" has not been registered.");

        return this.agentsToEntities.get(agent);
    }

    /*
     * (non-Javadoc)
     * 
     * @see eis.NewInterface#getAssociatedAgents(java.lang.String)
     */
    public final HashSet<String> getAssociatedAgents(String entity)
            throws EntityException {

        if (entities.contains(entity) == false)
            throw new EntityException("Entity \"" + entity
                    + "\" has not been registered.");

        HashSet<String> ret = new HashSet<String>();

        for (Entry<String, HashSet<String>> entry : agentsToEntities.entrySet()) {

            if (entry.getValue().contains(entity))
                ret.add(entry.getKey());

        }
        return ret;
    }

    /*
     * (non-Javadoc)
     * 
     * @see eis.NewInterface#getFreeEntities()
     */
    @SuppressWarnings("unchecked")
    public final LinkedList<String> getFreeEntities() {
        return (LinkedList<String>) freeEntities.clone();
    }

    /*
     * Acting/perceiving functionality.
     */


    /**
     * Performs EIS action in the original 2APL environment. Overrides standard
     * mechanism defined in
     * {@link eis.EIDefaultImpl#performAction(String, Action, String...)}.
     * <p />
     * An action to be performed on a certain EIS entity is performed on the
     * 2APL environment agent of the same name. In order to comply to the
     * EIS specification, the return values from action calls are wrapped in the
     * {@link eis.illang.ActionResult} having name <tt>actionresult</tt>. 
     */ 
    public final LinkedList<Percept> performAction(String agent,
            Action action, String... entities) throws ActException,
            NoEnvironmentException {
        // unregistered agents cannot act
        if (registeredAgents.contains(agent) == false)
            throw new ActException("Agent \"" + agent + "\" is not registered.");

        // get the associated entities
        HashSet<String> associatedEntities = agentsToEntities.get(agent);

        // no associated entity/ies -> trivial reject
        if (associatedEntities == null || associatedEntities.size() == 0)
            throw new ActException("Agent \"" + agent
                    + "\" has no associated entities.");

        // entities that should perform the action
        HashSet<String> targetEntities = null;
        if (entities.length == 0) {
            targetEntities = associatedEntities;
        } else {
            targetEntities = new HashSet<String>();

            for (String entity : entities) {
                if (associatedEntities.contains(entity) == false)
                    throw new ActException("Entity \"" + entity
                            + "\" is not associated to agent \"" + agent
                            + "\".");

                targetEntities.add(entity);
            }
        }

        // Get the parameters and convert them to the 2APL data types.
        LinkedList<Parameter> eisParams = action.getParameters();
        // Parameters expressed using 2APL data types
        LinkedList<Term> params = new LinkedList<Term>();

        for (Parameter p : eisParams) {
            params.add(IILConverter.convert(p));
        }

        // targetEntities contains all entities that should perform the action
        // params contains all parameters

        // determine class parameters for finding the method
        // and store the parameters as objects

        Class<?>[] classParams = new Class[params.size() + 1];
        classParams[0] = String.class; // entity name
        for (int a = 0; a < params.size(); a++)
            classParams[a + 1] = params.get(a).getClass();

        // return value
        LinkedList<Percept> rets = new LinkedList<Percept>();

        try {
            // lookup the method
            Method m = this.getClass().getMethod(action.getName(), classParams);

            if (Class.forName("apapl.data.Term").isAssignableFrom(
                    m.getReturnType()) == false)
                throw new ActException("Wrong return-type");

            // invoke
            for (String entity : targetEntities) {
                Object[] objParams = new Object[params.size() + 1];
                objParams[0] = entity; // entity name
                for (int a = 0; a < params.size(); a++)
                    objParams[a + 1] = params.get(a);

                Term result = (Term) m.invoke(this, objParams);

                // Convert Term to ActionResult
                Percept ar = IILConverter.convertToActionResult(result);
                rets.add(ar);
            }

        } catch (ClassNotFoundException e) {
            throw new ActException("Class not found", e);
        } catch (SecurityException e) {
            throw new ActException("Security exception", e);
        } catch (NoSuchMethodException e) {
            throw new ActException("No such method", e);
        } catch (IllegalArgumentException e) {
            throw new ActException("Illegal argument", e);
        } catch (IllegalAccessException e) {
            throw new ActException("Illegal access", e);
        } catch (InvocationTargetException e) {
            
            // action has failed -> let fail
            if (e.getCause() instanceof ExternalActionFailedException)
                throw new ActException("Execution failed.", (Exception) e
                        .getCause()); // rethrow
            
            else if (e.getCause() instanceof NoEnvironmentException)
                throw (NoEnvironmentException) e.getCause(); // rethrow

            throw new ActException("Invocation target exception", e);
        }

        return rets;
    }

    /*
     * (non-Javadoc)
     * 
     * @see eis.NewInterface#getAllPercepts(java.lang.String, java.lang.String)
     */
    // TODO maybe use isConnencted here
    public final LinkedList<Percept> getAllPercepts(String agent,
            String... entities) throws PerceiveException,
            NoEnvironmentException {

        // fail if ther agent is not registered
        if (registeredAgents.contains(agent) == false)
            throw new PerceiveException("Agent \"" + agent
                    + "\" is not registered.");

        // get the associated entities
        HashSet<String> associatedEntities = agentsToEntities.get(agent);

        // fail if there are no associated entities
        if (associatedEntities == null || associatedEntities.size() == 0)
            throw new PerceiveException("Agent \"" + agent
                    + "\" has no associated entities.");

        // return value
        LinkedList<Percept> ret = new LinkedList<Percept>();

        // gather all percepts
        if (entities.length == 0) {

            for (String entity : associatedEntities)
                ret.addAll(getAllPerceptsFromEntity(entity));

        }
        // only from specified entities
        else {

            for (String entity : entities) {

                if (associatedEntities.contains(entity) == false)
                    throw new PerceiveException("Entity \"" + entity
                            + "\" has not been associated with the agent \""
                            + agent + "\".");

                ret.addAll(getAllPerceptsFromEntity(entity));
            }
        }
        return ret;
    }

    /**
     * Gets all percepts of an entity.
     * <p/>
     * This method must be overridden.
     * 
     * @param entity is the entity whose percepts should be retrieved.
     * @return a list of percepts.
     */
    protected LinkedList<Percept> getAllPerceptsFromEntity(String entity)
            throws PerceiveException, NoEnvironmentException {
        // TODO Implement this
        return new LinkedList<Percept>();
    }

    /*
     * Management functionality.
     */

    /*
     * (non-Javadoc)
     * 
     * @see eis.NewInterface#manageEnvironment(eis.iilang.EnvironmentCommand,
     * java.lang.String)
     */
    public void manageEnvironment(EnvironmentCommand command, String... args)
            throws ManagementException, NoEnvironmentException {
        // TODO Implement this
    }

    /*
     * Misc functionality.
     */

    /*
     * (non-Javadoc)
     * 
     * @see eis.NewInterface#release()
     */
    public void release() {

    }

    /*
     * (non-Javadoc)
     * 
     * @see eis.NewInterface#isConnected()
     */
    public boolean isConnected() {
        return true;
    }

	@Override
	public String getType(String arg0) throws EntityException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void manageEnvironment(EnvironmentCommand arg0)
			throws ManagementException, NoEnvironmentException {
	}

	@Override
	public String requiredVersion() {
		// TODO Auto-generated method stub
		return "0.2";
	}

}
