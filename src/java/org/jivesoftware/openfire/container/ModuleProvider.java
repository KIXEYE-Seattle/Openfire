package org.jivesoftware.openfire.container;

import org.jivesoftware.openfire.XMPPServer;
import org.slf4j.Logger;

import java.util.Collection;

/**
 * Manages the lifecyle and lookup of all the modules loaded into the system.  Unfortunately
 * OpenFire looks up Modules by expected implementation class so alternative implementations
 * will need to register as their bases class.
 */
public interface ModuleProvider {

    Module get(Class clazz);

    Collection<Module> values();

    boolean isEmpty();

    void initialize(XMPPServer xmpp, ClassLoader loader, Logger log);

    void shutdown(Logger log);
}
