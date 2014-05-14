package org.jivesoftware.openfire;

import org.jivesoftware.openfire.audit.spi.AuditManagerImpl;
import org.jivesoftware.openfire.clearspace.ClearspaceManager;
import org.jivesoftware.openfire.commands.AdHocCommandHandler;
import org.jivesoftware.openfire.component.InternalComponentManager;
import org.jivesoftware.openfire.container.Module;
import org.jivesoftware.openfire.container.ModuleProvider;
import org.jivesoftware.openfire.disco.IQDiscoInfoHandler;
import org.jivesoftware.openfire.disco.IQDiscoItemsHandler;
import org.jivesoftware.openfire.filetransfer.DefaultFileTransferManager;
import org.jivesoftware.openfire.filetransfer.proxy.FileTransferProxy;
import org.jivesoftware.openfire.handler.*;
import org.jivesoftware.openfire.mediaproxy.MediaProxyService;
import org.jivesoftware.openfire.muc.MultiUserChatManager;
import org.jivesoftware.openfire.net.MulticastDNSService;
import org.jivesoftware.openfire.pep.IQPEPHandler;
import org.jivesoftware.openfire.pep.IQPEPOwnerHandler;
import org.jivesoftware.openfire.pubsub.PubSubModule;
import org.jivesoftware.openfire.roster.RosterManager;
import org.jivesoftware.openfire.spi.*;
import org.jivesoftware.openfire.transport.TransportHandler;
import org.jivesoftware.openfire.update.UpdateManager;
import org.jivesoftware.openfire.vcard.VCardManager;
import org.jivesoftware.util.LocaleUtils;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;


public class DefaultModuleProvider implements ModuleProvider {

    private Map<Class, Module> modules = new LinkedHashMap<Class, Module>();
    private XMPPServer xmmp;
    private ClassLoader loader;
    private Logger log;

    @Override
    public Module get(Class clazz) {
        return modules.get(clazz);
    }

    @Override
    public Collection<Module> values() {
        return modules.values();
    }

    @Override
    public boolean isEmpty() {
        return modules.isEmpty();
    }

    @Override
    public void initialize(XMPPServer xmpp, ClassLoader loader, Logger log) {
        this.xmmp = xmpp;
        this.loader = loader;
        this.log = log;
        modules.clear();
        loadModules();
        initModules();
        startModules();
        this.xmmp = null;
        this.loader = null;
        this.log = null;
    }

    @Override
    public void shutdown(Logger log) {
        for (Module module : modules.values()) {
            try {
                module.stop();
                module.destroy();
            } catch (Exception ex) {
                log.error("Exception during module shutdown", ex);
            }
        }
        modules.clear();
    }

    private void loadModules() {
        // Load boot modules
        loadModule(RoutingTableImpl.class.getName());
        loadModule(AuditManagerImpl.class.getName());
        loadModule(RosterManager.class.getName());
        loadModule(PrivateStorage.class.getName());
        // Load core modules
        loadModule(PresenceManagerImpl.class.getName());
        loadModule(SessionManager.class.getName());
        loadModule(PacketRouterImpl.class.getName());
        loadModule(IQRouter.class.getName());
        loadModule(MessageRouter.class.getName());
        loadModule(PresenceRouter.class.getName());
        loadModule(MulticastRouter.class.getName());
        loadModule(PacketTransporterImpl.class.getName());
        loadModule(PacketDelivererImpl.class.getName());
        loadModule(TransportHandler.class.getName());
        loadModule(OfflineMessageStrategy.class.getName());
        loadModule(OfflineMessageStore.class.getName());
        loadModule(VCardManager.class.getName());
        // Load standard modules
        loadModule(IQBindHandler.class.getName());
        loadModule(IQSessionEstablishmentHandler.class.getName());
        loadModule(IQAuthHandler.class.getName());
        loadModule(IQPingHandler.class.getName());
        loadModule(IQPrivateHandler.class.getName());
        loadModule(IQRegisterHandler.class.getName());
        loadModule(IQRosterHandler.class.getName());
        loadModule(IQTimeHandler.class.getName());
        loadModule(IQEntityTimeHandler.class.getName());
        loadModule(IQvCardHandler.class.getName());
        loadModule(IQVersionHandler.class.getName());
        loadModule(IQLastActivityHandler.class.getName());
        loadModule(PresenceSubscribeHandler.class.getName());
        loadModule(PresenceUpdateHandler.class.getName());
        loadModule(IQOfflineMessagesHandler.class.getName());
        loadModule(IQPEPHandler.class.getName());
        loadModule(IQPEPOwnerHandler.class.getName());
        loadModule(MulticastDNSService.class.getName());
        loadModule(IQSharedGroupHandler.class.getName());
        loadModule(AdHocCommandHandler.class.getName());
        loadModule(IQPrivacyHandler.class.getName());
        loadModule(DefaultFileTransferManager.class.getName());
        loadModule(FileTransferProxy.class.getName());
        loadModule(MediaProxyService.class.getName());
        loadModule(PubSubModule.class.getName());
        loadModule(IQDiscoInfoHandler.class.getName());
        loadModule(IQDiscoItemsHandler.class.getName());
        loadModule(UpdateManager.class.getName());
        loadModule(FlashCrossDomainHandler.class.getName());
        loadModule(InternalComponentManager.class.getName());
        loadModule(MultiUserChatManager.class.getName());
        loadModule(ClearspaceManager.class.getName());
        loadModule(IQMessageCarbonsHandler.class.getName());

        // Load this module always last since we don't want to start listening for clients
        // before the rest of the modules have been started
        loadModule(ConnectionManagerImpl.class.getName());
    }

    private void initModules() {
        for (Module module : modules.values()) {
            boolean isInitialized = false;
            try {
                module.initialize(xmmp);
                isInitialized = true;
            }
            catch (Exception e) {
                e.printStackTrace();
                // Remove the failed initialized module
                this.modules.remove(module.getClass());
                if (isInitialized) {
                    module.stop();
                    module.destroy();
                }
                log.error(LocaleUtils.getLocalizedString("admin.error"), e);
            }
        }
    }

    private void startModules() {
        for (Module module : modules.values()) {
            boolean started = false;
            try {
                module.start();
            }
            catch (Exception e) {
                if (started && module != null) {
                    module.stop();
                    module.destroy();
                }
                log.error(LocaleUtils.getLocalizedString("admin.error"), e);
            }
        }
    }

    private void loadModule(String module) {
        try {
            Class modClass = loader.loadClass(module);
            Module mod = (Module) modClass.newInstance();
            this.modules.put(modClass, mod);
        }
        catch (Exception e) {
            e.printStackTrace();
            log.error(LocaleUtils.getLocalizedString("admin.error"), e);
        }
    }
}
