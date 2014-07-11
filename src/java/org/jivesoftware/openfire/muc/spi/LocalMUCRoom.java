/**
 * $RCSfile$
 * $Revision: 3158 $
 * $Date: 2005-12-04 22:55:49 -0300 (Sun, 04 Dec 2005) $
 *
 * Copyright (C) 2004-2008 Jive Software. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jivesoftware.openfire.muc.spi;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.dom4j.Element;
import org.jivesoftware.database.SequenceManager;
import org.jivesoftware.openfire.PacketRouter;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.auth.UnauthorizedException;
import org.jivesoftware.openfire.cluster.NodeID;
import org.jivesoftware.openfire.muc.CannotBeInvitedException;
import org.jivesoftware.openfire.muc.ConflictException;
import org.jivesoftware.openfire.muc.ForbiddenException;
import org.jivesoftware.openfire.muc.HistoryRequest;
import org.jivesoftware.openfire.muc.HistoryStrategy;
import org.jivesoftware.openfire.muc.MUCEventDispatcher;
import org.jivesoftware.openfire.muc.MUCRole;
import org.jivesoftware.openfire.muc.MUCRoom;
import org.jivesoftware.openfire.muc.MUCRoomHistory;
import org.jivesoftware.openfire.muc.MultiUserChatService;
import org.jivesoftware.openfire.muc.NotAcceptableException;
import org.jivesoftware.openfire.muc.NotAllowedException;
import org.jivesoftware.openfire.muc.RegistrationRequiredException;
import org.jivesoftware.openfire.muc.RoomLockedException;
import org.jivesoftware.openfire.muc.ServiceUnavailableException;
import org.jivesoftware.openfire.muc.cluster.AddAffiliation;
import org.jivesoftware.openfire.muc.cluster.AddMember;
import org.jivesoftware.openfire.muc.cluster.BroadcastMessageRequest;
import org.jivesoftware.openfire.muc.cluster.BroadcastPresenceRequest;
import org.jivesoftware.openfire.muc.cluster.ChangeNickname;
import org.jivesoftware.openfire.muc.cluster.DestroyRoomRequest;
import org.jivesoftware.openfire.muc.cluster.OccupantAddedEvent;
import org.jivesoftware.openfire.muc.cluster.OccupantLeftEvent;
import org.jivesoftware.openfire.muc.cluster.RoomUpdatedEvent;
import org.jivesoftware.openfire.muc.cluster.UpdateOccupant;
import org.jivesoftware.openfire.muc.cluster.UpdateOccupantRequest;
import org.jivesoftware.openfire.muc.cluster.UpdatePresence;
import org.jivesoftware.openfire.user.UserAlreadyExistsException;
import org.jivesoftware.openfire.user.UserNotFoundException;
import org.jivesoftware.util.JiveConstants;
import org.jivesoftware.util.LocaleUtils;
import org.jivesoftware.util.NotFoundException;
import org.jivesoftware.util.cache.CacheFactory;
import org.jivesoftware.util.cache.ExternalizableUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;
import org.xmpp.packet.Packet;
import org.xmpp.packet.PacketError;
import org.xmpp.packet.Presence;

/**
 * Implementation of a chatroom that is being hosted by this JVM. A LocalMUCRoom could represent
 * a persistent room which means that its configuration will be maintained in synch with its
 * representation in the database.<p>
 *
 * When running in a cluster each cluster node will have its own copy of local rooms. Persistent
 * rooms will be loaded by each cluster node when starting up. Not persistent rooms will be copied
 * from the senior cluster member. All room occupants will be copied from the senior cluster member
 * too.
 * 
 * @author Gaston Dombiak
 */
public class LocalMUCRoom implements MUCRoom {

	private static final Logger Log = LoggerFactory.getLogger(LocalMUCRoom.class);

    /**
     * The service hosting the room.
     */
    private MultiUserChatService mucService;

    /**
     * The occupants of the room accessible by the occupants nickname.
     */
    private Map<String, List<MUCRole>> occupantsByNickname = new ConcurrentHashMap<String, List<MUCRole>>();

    /**
     * The occupants of the room accessible by the occupants bare JID.
     */
    private Map<JID, List<MUCRole>> occupantsByBareJID = new ConcurrentHashMap<JID, List<MUCRole>>();

    /**
     * The occupants of the room accessible by the occupants full JID.
     */
    private Map<JID, MUCRole> occupantsByFullJID = new ConcurrentHashMap<JID, MUCRole>();

    /**
     * The name of the room.
     */
    private String name;

    /**
     * A lock to protect the room occupants.
     */
    ReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * The role of the room itself.
     */
    private MUCRole role = new RoomRole(this);

    /**
     * The router used to send packets for the room.
     */
    private PacketRouter router;

    /**
     * The start time of the chat.
     */
    long startTime;

    /**
     * The end time of the chat.
     */
    long endTime;

    /**
     * After a room has been destroyed it may remain in memory but it won't be possible to use it.
     * When a room is destroyed it is immediately removed from the MultiUserChatService but it's
     * possible that while the room was being destroyed it was being used by another thread so we
     * need to protect the room under these rare circumstances.
     */
    boolean isDestroyed = false;

    /**
     * ChatRoomHistory object.
     */
    private MUCRoomHistory roomHistory;

    /**
     * Time when the room was locked. A value of zero means that the room is unlocked.
     */
    private long lockedTime;

    /**
     * List of chatroom's owner. The list contains only bare jid.
     */
    List<JID> owners = new CopyOnWriteArrayList<JID>();

    /**
     * List of chatroom's admin. The list contains only bare jid.
     */
    List<JID> admins = new CopyOnWriteArrayList<JID>();

    /**
     * List of chatroom's members. The list contains only bare jid, mapped to a nickname.
     */
    private Map<JID, String> members = new ConcurrentHashMap<JID,String>();

    /**
     * List of chatroom's outcast. The list contains only bare jid of not allowed users.
     */
    private List<JID> outcasts = new CopyOnWriteArrayList<JID>();

    /**
     * The natural language name of the room.
     */
    private String naturalLanguageName;

    /**
     * Description of the room. The owner can change the description using the room configuration
     * form.
     */
    private String description;

    /**
     * Indicates if occupants are allowed to change the subject of the room.
     */
    private boolean canOccupantsChangeSubject;

    /**
     * Maximum number of occupants that could be present in the room. If the limit's been reached
     * and a user tries to join, a not-allowed error will be returned.
     */
    private int maxUsers;

    /**
     * List of roles of which presence will be broadcasted to the rest of the occupants. This
     * feature is useful for implementing "invisible" occupants.
     */
    private List<String> rolesToBroadcastPresence = new ArrayList<String>();

    /**
     * Blocking initial presence means that a user joining a room does not get presence
     * information for the existing members.  Also, existing members are not notified of
     * new user joining the room.  This is a more granular switch than doing it per role.
     */
    private boolean blockInitialPresence;

    /**
     * A public room means that the room is searchable and visible. This means that the room can be
     * located using disco requests.
     */
    private boolean publicRoom;

    /**
     * Persistent rooms are saved to the database to make sure that rooms configurations can be
     * restored in case the server goes down.
     */
    private boolean persistent;

    /**
     * Moderated rooms enable only participants to speak. Users that join the room and aren't
     * participants can't speak (they are just visitors).
     */
    private boolean moderated;

    /**
     * A room is considered members-only if an invitation is required in order to enter the room.
     * Any user that is not a member of the room won't be able to join the room unless the user
     * decides to register with the room (thus becoming a member).
     */
    private boolean membersOnly;

    /**
     * Some rooms may restrict the occupants that are able to send invitations. Sending an
     * invitation in a members-only room adds the invitee to the members list.
     */
    private boolean canOccupantsInvite;

    /**
     * The password that every occupant should provide in order to enter the room.
     */
    private String password = null;

    /**
     * Every presence packet can include the JID of every occupant unless the owner deactives this
     * configuration.
     */
    private boolean canAnyoneDiscoverJID;

    /**
     * Enables the logging of the conversation. The conversation in the room will be saved to the
     * database.
     */
    private boolean logEnabled;

    /**
     * Enables the logging of the conversation. The conversation in the room will be saved to the
     * database.
     */
    private boolean loginRestrictedToNickname;

    /**
     * Enables the logging of the conversation. The conversation in the room will be saved to the
     * database.
     */
    private boolean canChangeNickname;

    /**
     * Enables the logging of the conversation. The conversation in the room will be saved to the
     * database.
     */
    private boolean registrationEnabled;

    /**
     * Internal component that handles IQ packets sent by the room owners.
     */
    private IQOwnerHandler iqOwnerHandler;

    /**
     * Internal component that handles IQ packets sent by moderators, admins and owners.
     */
    private IQAdminHandler iqAdminHandler;

    /**
     * The last known subject of the room. This information is used to respond disco requests. The
     * MUCRoomHistory class holds the history of the room together with the last message that set
     * the room's subject.
     */
    private String subject = "";

    /**
     * The ID of the room. If the room is temporary and does not log its conversation then the value
     * will always be -1. Otherwise a value will be obtained from the database.
     */
    private long roomID = -1;

    /**
     * The date when the room was created.
     */
    private Date creationDate;

    /**
     * The last date when the room's configuration was modified.
     */
    private Date modificationDate;

    /**
     * The date when the last occupant left the room. A null value means that there are occupants
     * in the room at the moment.
     */
    private Date emptyDate;

    /**
     * Indicates if the room is present in the database.
     */
    private boolean savedToDB = false;

    /**
     * Do not use this constructor. It was added to implement the Externalizable
     * interface required to work inside of a cluster.
     */
    public LocalMUCRoom() {
    }

    /**
     * Create a new chat room.
     *
     * @param chatservice the service hosting the room.
     * @param roomname the name of the room.
     * @param packetRouter the router for sending packets from the room.
     */
    LocalMUCRoom(MultiUserChatService chatservice, String roomname, PacketRouter packetRouter) {
        this.mucService = chatservice;
        this.name = roomname;
        this.naturalLanguageName = roomname;
        this.description = roomname;
        this.router = packetRouter;
        this.startTime = System.currentTimeMillis();
        this.creationDate = new Date(startTime);
        this.modificationDate = new Date(startTime);
        this.emptyDate = new Date(startTime);
        this.canOccupantsChangeSubject = MUCPersistenceManager.getBooleanProperty(mucService.getServiceName(), "room.canOccupantsChangeSubject", false);
        this.maxUsers = MUCPersistenceManager.getIntProperty(mucService.getServiceName(), "room.maxUsers", 30);
        this.publicRoom = MUCPersistenceManager.getBooleanProperty(mucService.getServiceName(), "room.publicRoom", true);
        this.persistent = MUCPersistenceManager.getBooleanProperty(mucService.getServiceName(), "room.persistent", false);
        this.moderated = MUCPersistenceManager.getBooleanProperty(mucService.getServiceName(), "room.moderated", false);
        this.membersOnly = MUCPersistenceManager.getBooleanProperty(mucService.getServiceName(), "room.membersOnly", false);
        this.canOccupantsInvite = MUCPersistenceManager.getBooleanProperty(mucService.getServiceName(), "room.canOccupantsInvite", false);
        this.canAnyoneDiscoverJID = MUCPersistenceManager.getBooleanProperty(mucService.getServiceName(), "room.canAnyoneDiscoverJID", true);
        this.logEnabled = MUCPersistenceManager.getBooleanProperty(mucService.getServiceName(), "room.logEnabled", false);
        this.loginRestrictedToNickname = MUCPersistenceManager.getBooleanProperty(mucService.getServiceName(), "room.loginRestrictedToNickname", false);
        this.canChangeNickname = MUCPersistenceManager.getBooleanProperty(mucService.getServiceName(), "room.canChangeNickname", true);
        this.registrationEnabled = MUCPersistenceManager.getBooleanProperty(mucService.getServiceName(), "room.registrationEnabled", true);
        // TODO Allow to set the history strategy from the configuration form?
        roomHistory = new MUCRoomHistory(this, new HistoryStrategy(mucService.getHistoryStrategy()));
        this.iqOwnerHandler = new IQOwnerHandler(this, packetRouter);
        this.iqAdminHandler = new IQAdminHandler(this, packetRouter);
        // No one can join the room except the room's owner
        this.lockedTime = startTime;
        // Set the default roles for which presence is broadcast
        rolesToBroadcastPresence.add("moderator");
        rolesToBroadcastPresence.add("participant");
        rolesToBroadcastPresence.add("visitor");
    }

    public String getName() {
        return name;
    }

    public JID getJID() {
        return new JID(getName(), getMUCService().getServiceDomain(), null);
    }

    public MultiUserChatService getMUCService() {
        return mucService;
    }

    public void setMUCService(MultiUserChatService service) {
        this.mucService = service;
    }

    public long getID() {
        if (isPersistent() || isLogEnabled()) {
            if (roomID == -1) {
                roomID = SequenceManager.nextID(JiveConstants.MUC_ROOM);
            }
        }
        return roomID;
    }

    public void setID(long roomID) {
        this.roomID = roomID;
    }

    public Date getCreationDate() {
        return creationDate;
    }

    public void setCreationDate(Date creationDate) {
        this.creationDate = creationDate;
    }

    public Date getModificationDate() {
        return modificationDate;
    }

    public void setModificationDate(Date modificationDate) {
        this.modificationDate = modificationDate;
    }

    public void setEmptyDate(Date emptyDate) {
        // Do nothing if old value is same as new value
        if (this.emptyDate == emptyDate) {
            return;
        }
        this.emptyDate = emptyDate;
        MUCPersistenceManager.updateRoomEmptyDate(this);
    }

    public Date getEmptyDate() {
        return this.emptyDate;
    }

    public MUCRole getRole() {
        return role;
    }

    /**
     * @deprecated Prefer {@link #getOccupantsByNickname(String)} (user can be connected more than once)
     */
    public MUCRole getOccupant(String nickname) throws UserNotFoundException {
        if (nickname == null) {
             throw new UserNotFoundException();
        }
        List<MUCRole> roles = getOccupantsByNickname(nickname);
        if (roles != null && roles.size() > 0) {
        	return roles.get(0);
        }
        throw new UserNotFoundException();
    }

    public List<MUCRole> getOccupantsByNickname(String nickname) throws UserNotFoundException {
        if (nickname == null) {
             throw new UserNotFoundException();
        }
        List<MUCRole> roles = occupantsByNickname.get(nickname.toLowerCase());
        if (roles != null && roles.size() > 0) {
        	return roles;
        }
        throw new UserNotFoundException();
    }

    public List<MUCRole> getOccupantsByBareJID(JID jid) throws UserNotFoundException {
        List<MUCRole> roles = occupantsByBareJID.get(jid);
        if (roles != null && !roles.isEmpty()) {
            return Collections.unmodifiableList(roles);
        }
        throw new UserNotFoundException();
    }

    public MUCRole getOccupantByFullJID(JID jid) {
        MUCRole role = occupantsByFullJID.get(jid);
        if (role != null) {
            return role;
        }
        return null;
    }

    public Collection<MUCRole> getOccupants() {
        return Collections.unmodifiableCollection(occupantsByFullJID.values());
    }

    public int getOccupantsCount() {
        return occupantsByFullJID.size();
    }

    public boolean hasOccupant(String nickname) {
        return occupantsByNickname.containsKey(nickname.toLowerCase());
    }

    public String getReservedNickname(JID jid) {
    	final JID bareJID = jid.asBareJID();
        String answer = members.get(bareJID);
        if (answer == null || answer.trim().length() == 0) {
            return null;
        }
        return answer;
    }

    public MUCRole.Affiliation getAffiliation(JID jid) {
    	final JID bareJID = jid.asBareJID();

        if (owners.contains(bareJID)) {
            return MUCRole.Affiliation.owner;
        }
        else if (admins.contains(bareJID)) {
            return MUCRole.Affiliation.admin;
        }
        else if (members.containsKey(bareJID)) {
            return MUCRole.Affiliation.member;
        }
        else if (outcasts.contains(bareJID)) {
            return MUCRole.Affiliation.outcast;
        }
        return MUCRole.Affiliation.none;
    }

    public LocalMUCRole joinRoom(String nickname, String password, HistoryRequest historyRequest,
            LocalMUCUser user, Presence presence) throws UnauthorizedException,
            UserAlreadyExistsException, RoomLockedException, ForbiddenException,
            RegistrationRequiredException, ConflictException, ServiceUnavailableException,
            NotAcceptableException {
        if (((MultiUserChatServiceImpl)mucService).getMUCDelegate() != null) {
            if (!((MultiUserChatServiceImpl)mucService).getMUCDelegate().joiningRoom(this, user.getAddress())) {
                // Delegate said no, reject join.
                throw new UnauthorizedException();
            }
        }
        LocalMUCRole joinRole = null;
        lock.writeLock().lock();
        try {
            // If the room has a limit of max user then check if the limit has been reached
            if (!canJoinRoom(user)) {
                throw new ServiceUnavailableException();
            }
            final JID bareJID = user.getAddress().asBareJID();
			boolean isOwner = owners.contains(bareJID);
            // If the room is locked and this user is not an owner raise a RoomLocked exception
            if (isLocked()) {
                if (!isOwner) {
                    throw new RoomLockedException();
                }
            }
            // Check if the nickname is already used in the room
            if (occupantsByNickname.containsKey(nickname.toLowerCase())) {
            	List<MUCRole> occupants = occupantsByNickname.get(nickname.toLowerCase());
            	MUCRole occupant = occupants.size() > 0 ? occupants.get(0) : null;
                if (occupant != null && !occupant.getUserAddress().toBareJID().equals(bareJID.toBareJID())) {
                    // Nickname is already used, and not by the same JID
                    throw new UserAlreadyExistsException();
                }
            }
            // If the room is password protected and the provided password is incorrect raise a
            // Unauthorized exception
            if (isPasswordProtected()) {
                if (password == null || !password.equals(getPassword())) {
                    throw new UnauthorizedException();
                }
            }
            // If another user attempts to join the room with a nickname reserved by the first user
            // raise a ConflictException
            if (members.containsValue(nickname.toLowerCase())) {
                if (!nickname.toLowerCase().equals(members.get(bareJID))) {
                    throw new ConflictException();
                }
            }
            if (isLoginRestrictedToNickname()) {
                String reservedNickname = members.get(bareJID);
                if (reservedNickname != null && !nickname.toLowerCase().equals(reservedNickname)) {
                    throw new NotAcceptableException();
                }
            }

            // Set the corresponding role based on the user's affiliation
            MUCRole.Role role;
            MUCRole.Affiliation affiliation;
            if (isOwner) {
                // The user is an owner. Set the role and affiliation accordingly.
                role = MUCRole.Role.moderator;
                affiliation = MUCRole.Affiliation.owner;
            }
            else if (mucService.getSysadmins().contains(bareJID)) {
                // The user is a system administrator of the MUC service. Treat him as an owner
                // although he won't appear in the list of owners
                role = MUCRole.Role.moderator;
                affiliation = MUCRole.Affiliation.owner;
            }
            else if (admins.contains(bareJID)) {
                // The user is an admin. Set the role and affiliation accordingly.
                role = MUCRole.Role.moderator;
                affiliation = MUCRole.Affiliation.admin;
            }
            else if (members.containsKey(bareJID)) {
                // The user is a member. Set the role and affiliation accordingly.
                role = MUCRole.Role.participant;
                affiliation = MUCRole.Affiliation.member;
            }
            else if (outcasts.contains(bareJID)) {
                // The user is an outcast. Raise a "Forbidden" exception.
                throw new ForbiddenException();
            }
            else {
                // The user has no affiliation (i.e. NONE). Set the role accordingly.
                if (isMembersOnly()) {
                    // The room is members-only and the user is not a member. Raise a
                    // "Registration Required" exception.
                    throw new RegistrationRequiredException();
                }
                role = (isModerated() ? MUCRole.Role.visitor : MUCRole.Role.participant);
                affiliation = MUCRole.Affiliation.none;
            }
            // Create a new role for this user in this room
            joinRole = new LocalMUCRole(mucService, this, nickname, role, affiliation, user, presence, router);
            // Add the new user as an occupant of this room
            List<MUCRole> occupants = occupantsByNickname.get(nickname.toLowerCase());
            if (occupants == null) {
            	occupants = new CopyOnWriteArrayList<MUCRole>();
            	occupantsByNickname.put(nickname.toLowerCase(), occupants);
            }
            occupants.add(joinRole);
            // Update the tables of occupants based on the bare and full JID
            List<MUCRole> list = occupantsByBareJID.get(bareJID);
            if (list == null) {
                list = new ArrayList<MUCRole>();
                occupantsByBareJID.put(bareJID, list);
            }
            list.add(joinRole);
            occupantsByFullJID.put(user.getAddress(), joinRole);
        }
        finally {
            lock.writeLock().unlock();
        }
        // Notify other cluster nodes that a new occupant joined the room
        CacheFactory.doClusterTask(new OccupantAddedEvent(this, joinRole));

        // Send presence of existing occupants to new occupant
        sendInitialPresences(joinRole);
        // It is assumed that the room is new based on the fact that it's locked and
        // that it was locked when it was created.
        boolean isRoomNew = isLocked() && creationDate.getTime() == lockedTime;
        try {
            // Send the presence of this new occupant to existing occupants
            Presence joinPresence = joinRole.getPresence().createCopy();
            broadcastPresence(joinPresence, true);
        }
        catch (Exception e) {
            Log.error(LocaleUtils.getLocalizedString("admin.error"), e);
        }
        // If the room has just been created send the "room locked until configuration is
        // confirmed" message
        if (!isRoomNew && isLocked()) {
            // http://xmpp.org/extensions/xep-0045.html#enter-locked
            Presence presenceItemNotFound = new Presence(Presence.Type.error);
            presenceItemNotFound.setError(PacketError.Condition.item_not_found);
            presenceItemNotFound.setFrom(role.getRoleAddress());
            joinRole.send(presenceItemNotFound);

        }
        if (historyRequest == null) {
            Iterator<Message> history = roomHistory.getMessageHistory();
            while (history.hasNext()) {
                joinRole.send(history.next());
            }
        }
        else {
            historyRequest.sendHistory(joinRole, roomHistory);
        }
        // Update the date when the last occupant left the room
        setEmptyDate(null);
        // Fire event that occupant joined the room
        MUCEventDispatcher.occupantJoined(getRole().getRoleAddress(), user.getAddress(), joinRole.getNickname());
        return joinRole;
    }

    /**
     * Can a user join this room
     * 
     * @param user the user attempting to join this room
     * @return boolean
     */
    private boolean canJoinRoom(LocalMUCUser user){
    	boolean isOwner = owners.contains(user.getAddress().toBareJID());
    	boolean isAdmin = admins.contains(user.getAddress().toBareJID());
    	return (!isDestroyed && (!hasOccupancyLimit() || isAdmin || isOwner || (getOccupantsCount() < getMaxUsers())));
    }

    /**
     * Does this room have an occupancy limit?
     * 
     * @return boolean
     */
    private boolean hasOccupancyLimit(){
    	return getMaxUsers() != 0;
    }
    
    /**
     * Sends presence of existing occupants to new occupant.
     *
     * @param joinRole the role of the new occupant in the room.
     */
    private void sendInitialPresences(LocalMUCRole joinRole) {
        if (blockInitialPresence()) {
            return;
        } else {
            Log.warn("Room " + getName() + "is sending initial presence");
        }
        for (MUCRole occupant : occupantsByFullJID.values()) {
            if (occupant == joinRole) {
                continue;
            }
            Presence occupantPresence = occupant.getPresence();
            // Skip to the next occupant if we cannot send presence of this occupant
            if (hasToCheckRoleToBroadcastPresence()) {
                Element frag = occupantPresence.getChildElement("x",
                        "http://jabber.org/protocol/muc#user");
                // Check if we can broadcast the presence for this role
                if (!canBroadcastPresence(frag.element("item").attributeValue("role"))) {
                    continue;
                }
            }
            // Don't include the occupant's JID if the room is semi-anon and the new occupant
            // is not a moderator
            if (!canAnyoneDiscoverJID() && MUCRole.Role.moderator != joinRole.getRole()) {
                occupantPresence = occupantPresence.createCopy();
                Element frag = occupantPresence.getChildElement("x",
                        "http://jabber.org/protocol/muc#user");
                frag.element("item").addAttribute("jid", null);
            }
            joinRole.send(occupantPresence);
        }
    }

    public void occupantAdded(OccupantAddedEvent event) {
        // Create a proxy for the occupant that joined the room from another cluster node
        RemoteMUCRole joinRole = new RemoteMUCRole(mucService, event);
        JID bareJID = event.getUserAddress().asBareJID();
        String nickname = event.getNickname();
        List<MUCRole> occupants = occupantsByNickname.get(nickname.toLowerCase());
        // Do not add new occupant with one with same nickname already exists
        if (occupants == null) {
            occupants = new CopyOnWriteArrayList<MUCRole>();
            occupantsByNickname.put(nickname.toLowerCase(), occupants);
        } else {
        	// sanity check; make sure the nickname is owned by the same JID
        	if (occupants.size() > 0) {
        		String existingJID = occupants.get(0).getUserAddress().toBareJID();
        		if (!bareJID.equals(existingJID)) {
        			Log.warn(MessageFormat.format("Conflict detected; {0} requested nickname '{1}'; already being used by {2}", bareJID, nickname, existingJID));
        			return;
        		}
        	}
        }
        // Add the new user as an occupant of this room
        occupants.add(joinRole);
        // Update the tables of occupants based on the bare and full JID
		List<MUCRole> list = occupantsByBareJID.get(bareJID);
        if (list == null) {
            list = new ArrayList<MUCRole>();
            occupantsByBareJID.put(bareJID, list);
        }
        list.add(joinRole);
        occupantsByFullJID.put(event.getUserAddress(), joinRole);

        // Update the date when the last occupant left the room
        setEmptyDate(null);
        if (event.isOriginator()) {
            // Fire event that occupant joined the room
            MUCEventDispatcher.occupantJoined(getRole().getRoleAddress(), event.getUserAddress(), joinRole.getNickname());
        }
        // Check if we need to send presences of the new occupant to occupants hosted by this JVM
        if (event.isSendPresence()) {
            for (MUCRole occupant : occupantsByFullJID.values()) {
                if (occupant.isLocal()) {
                    occupant.send(event.getPresence().createCopy());
                }
            }
        }
    }

    public void leaveRoom(MUCRole leaveRole) {
        if (leaveRole.isLocal()) {
            // Ask other cluster nodes to remove occupant from room
            OccupantLeftEvent event = new OccupantLeftEvent(this, leaveRole);
            CacheFactory.doClusterTask(event);
        }

        try {
            Presence originalPresence = leaveRole.getPresence();
            Presence presence = originalPresence.createCopy();
            presence.setType(Presence.Type.unavailable);
            presence.setStatus(null);
            // Change (or add) presence information about roles and affiliations
            Element childElement = presence.getChildElement("x", "http://jabber.org/protocol/muc#user");
            if (childElement == null) {
                childElement = presence.addChildElement("x", "http://jabber.org/protocol/muc#user");
            }
            Element item = childElement.element("item");
            if (item == null) {
                item = childElement.addElement("item");
            }
            item.addAttribute("role", "none");

            // Check to see if the user's original presence is one we should broadcast
            // a leave packet for. Need to check the original presence because we just
            // set the role to "none" above, which is always broadcast.
            if(!shouldBroadcastPresence(originalPresence)){
                // Inform the leaving user that he/she has left the room
                leaveRole.send(presence);
            }
            else {
            	if (getOccupantsByNickname(leaveRole.getNickname()).size() <= 1) {
                    // Inform the rest of the room occupants that the user has left the room
                    broadcastPresence(presence, false);
            	}
            }
        }
        catch (Exception e) {
            Log.error(e.getMessage(), e);
        }

        // Remove occupant from room and destroy room if empty and not persistent
        OccupantLeftEvent event = new OccupantLeftEvent(this, leaveRole);
        event.setOriginator(true);
        event.run();
    }

    public void leaveRoom(OccupantLeftEvent event) {
        MUCRole leaveRole = event.getRole();
        if (leaveRole == null) {
            return;
        }
        lock.writeLock().lock();
        try {
            // Removes the role from the room
            removeOccupantRole(leaveRole, event.isOriginator());

            // TODO Implement this: If the room owner becomes unavailable for any reason before
            // submitting the form (e.g., a lost connection), the service will receive a presence
            // stanza of type "unavailable" from the owner to the room@service/nick or room@service
            // (or both). The service MUST then destroy the room, sending a presence stanza of type
            // "unavailable" from the room to the owner including a <destroy/> element and reason
            // (if provided) as defined under the "Destroying a Room" use case.

            // Remove the room from the service only if there are no more occupants and the room is
            // not persistent
            if (occupantsByFullJID.isEmpty() && !isPersistent()) {
                endTime = System.currentTimeMillis();
                if (event.isOriginator()) {
                    mucService.removeChatRoom(name);
                    // Fire event that the room has been destroyed
                    MUCEventDispatcher.roomDestroyed(getRole().getRoleAddress());
                }
            }
            if (occupantsByFullJID.isEmpty()) {
                // Update the date when the last occupant left the room
                setEmptyDate(new Date());
            }
        }
        finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Removes the role of the occupant from all the internal occupants collections. The role will
     * also be removed from the user's roles.
     *
     * @param leaveRole the role to remove.
     * @param originator true if this JVM is the one that originated the event.
     */
    private void removeOccupantRole(MUCRole leaveRole, boolean originator) {
        JID userAddress = leaveRole.getUserAddress();
        // Notify the user that he/she is no longer in the room
        leaveRole.destroy();
        // Update the tables of occupants based on the bare and full JID
        JID bareJID = userAddress.asBareJID();
        
        String nickname = leaveRole.getNickname();
    	List<MUCRole> occupants = occupantsByNickname.get(nickname.toLowerCase());
        if (occupants != null) {
        	occupants.remove(leaveRole);
            if (occupants.isEmpty()) {
                occupantsByNickname.remove(nickname.toLowerCase());
            }
        }
        List<MUCRole> list = occupantsByBareJID.get(bareJID);
        if (list != null) {
            list.remove(leaveRole);
            if (list.isEmpty()) {
                occupantsByBareJID.remove(bareJID);
            }
        }
        occupantsByFullJID.remove(userAddress);
        if (originator) {
            // Fire event that occupant left the room
            MUCEventDispatcher.occupantLeft(getRole().getRoleAddress(), userAddress);
        }
    }

    public void destroyRoom(DestroyRoomRequest destroyRequest) {
        JID alternateJID = destroyRequest.getAlternateJID();
        String reason = destroyRequest.getReason();
        Collection<MUCRole> removedRoles = new ArrayList<MUCRole>();
        lock.writeLock().lock();
        try {
            boolean hasRemoteOccupants = false;
            // Remove each occupant
            for (MUCRole leaveRole : occupantsByFullJID.values()) {

                if (leaveRole != null) {
                    // Add the removed occupant to the list of removed occupants. We are keeping a
                    // list of removed occupants to process later outside of the lock.
                    if (leaveRole.isLocal()) {
                        removedRoles.add(leaveRole);
                    }
                    else {
                        hasRemoteOccupants = true;
                    }
                    removeOccupantRole(leaveRole, destroyRequest.isOriginator());
                }
            }
            endTime = System.currentTimeMillis();
            // Set that the room has been destroyed
            isDestroyed = true;
            if (destroyRequest.isOriginator()) {
                if (hasRemoteOccupants) {
                    // Ask other cluster nodes to remove occupants since room is being destroyed
                    CacheFactory.doClusterTask(new DestroyRoomRequest(this, alternateJID, reason));
                }
                // Removes the room from the list of rooms hosted in the service
                mucService.removeChatRoom(name);
            }
        }
        finally {
            lock.writeLock().unlock();
        }
        // Send an unavailable presence to each removed occupant
        for (MUCRole removedRole : removedRoles) {
            try {
                // Send a presence stanza of type "unavailable" to the occupant
                Presence presence = createPresence(Presence.Type.unavailable);
                presence.setFrom(removedRole.getRoleAddress());

                // A fragment containing the x-extension for room destruction.
                Element fragment = presence.addChildElement("x",
                        "http://jabber.org/protocol/muc#user");
                Element item = fragment.addElement("item");
                item.addAttribute("affiliation", "none");
                item.addAttribute("role", "none");
                if (alternateJID != null) {
                    fragment.addElement("destroy").addAttribute("jid", alternateJID.toFullJID());
                }
                if (reason != null && reason.length() > 0) {
                    Element destroy = fragment.element("destroy");
                    if (destroy == null) {
                        destroy = fragment.addElement("destroy");
                    }
                    destroy.addElement("reason").setText(reason);
                }
                removedRole.send(presence);
            }
            catch (Exception e) {
                Log.error(e.getMessage(), e);
            }
        }
        if (destroyRequest.isOriginator()) {
            // Remove the room from the DB if the room was persistent
            MUCPersistenceManager.deleteFromDB(this);
            // Fire event that the room has been destroyed
            MUCEventDispatcher.roomDestroyed(getRole().getRoleAddress());
        }
    }

    public void destroyRoom(JID alternateJID, String reason) {
        DestroyRoomRequest destroyRequest = new DestroyRoomRequest(this, alternateJID, reason);
        destroyRequest.setOriginator(true);
        destroyRequest.run();
    }

    public Presence createPresence(Presence.Type presenceType) throws UnauthorizedException {
        Presence presence = new Presence();
        presence.setType(presenceType);
        presence.setFrom(role.getRoleAddress());
        return presence;
    }

    public void serverBroadcast(String msg) {
        Message message = new Message();
        message.setType(Message.Type.groupchat);
        message.setBody(msg);
        message.setFrom(role.getRoleAddress());
        broadcast(message);
    }

    public void sendPublicMessage(Message message, MUCRole senderRole) throws ForbiddenException {
        // Check that if the room is moderated then the sender of the message has to have voice
        if (isModerated() && senderRole.getRole().compareTo(MUCRole.Role.participant) > 0) {
            throw new ForbiddenException();
        }
        // Send the message to all occupants
        message.setFrom(senderRole.getRoleAddress());
        send(message);
        // Fire event that message was received by the room
        MUCEventDispatcher.messageReceived(getRole().getRoleAddress(), senderRole.getUserAddress(),
                senderRole.getNickname(), message);
    }

    public void sendPrivatePacket(Packet packet, MUCRole senderRole) throws NotFoundException {
        String resource = packet.getTo().getResource();
        List<MUCRole> occupants = occupantsByNickname.get(resource.toLowerCase());
        if (occupants == null || occupants.size() == 0) {
            throw new NotFoundException();
        }
        for (MUCRole occupant : occupants) {
            packet.setFrom(senderRole.getRoleAddress());
            occupant.send(packet);
            if(packet instanceof Message) {
               Message message = (Message) packet;
                 MUCEventDispatcher.privateMessageRecieved(occupant.getUserAddress(), senderRole.getUserAddress(),
                         message);
            }
        }
    }

    public void send(Packet packet) {
        if (packet instanceof Message) {
            broadcast((Message)packet);
        }
        else if (packet instanceof Presence) {
            broadcastPresence((Presence)packet, false);
        }
        else if (packet instanceof IQ) {
            IQ reply = IQ.createResultIQ((IQ) packet);
            reply.setChildElement(((IQ) packet).getChildElement());
            reply.setError(PacketError.Condition.bad_request);
            router.route(reply);
        }
    }

    /**
     * Checks the role of the sender and returns true if the given presence should be broadcasted
     * 
     * @param presence The presence to check
     * @return true if the presence should be broadcast to the rest of the room
     */
    private boolean shouldBroadcastPresence(Presence presence){
        if (presence == null) {
            return false;
        }
        if (blockInitialPresence()) {
            return false;
        } else {
            Log.warn("Room " + getName() + "is broadcasting presence");
        }
        if (hasToCheckRoleToBroadcastPresence()) {
            Element frag = presence.getChildElement("x", "http://jabber.org/protocol/muc#user");
            // Check if we can broadcast the presence for this role
            if (!canBroadcastPresence(frag.element("item").attributeValue("role"))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Broadcasts the specified presence to all room occupants. If the presence belongs to a
     * user whose role cannot be broadcast then the presence will only be sent to the presence's
     * user. On the other hand, the JID of the user that sent the presence won't be included if the
     * room is semi-anon and the target occupant is not a moderator.
     *
     * @param presence the presence to broadcast.
     * @param isJoinPresence If the presence is sent in the context of joining the room.
     */
    private void broadcastPresence(Presence presence, boolean isJoinPresence) {
        if (presence == null) {
            return;
        }
        if (!shouldBroadcastPresence(presence)) {
            // Just send the presence to the sender of the presence
            try {
                for (MUCRole occupant : getOccupantsByNickname(presence.getFrom().getResource())) {
                    occupant.send(presence);
                }
            }
            catch (UserNotFoundException e) {
                // Do nothing
            }
            return;
        }

        // Broadcast presence to occupants hosted by other cluster nodes
        BroadcastPresenceRequest request = new BroadcastPresenceRequest(this, presence, isJoinPresence);
        CacheFactory.doClusterTask(request);

        // Broadcast presence to occupants connected to this JVM
        request = new BroadcastPresenceRequest(this, presence, isJoinPresence);
        request.setOriginator(true);
        request.run();
    }

    public void broadcast(BroadcastPresenceRequest presenceRequest) {
        String jid = null;
        Presence presence = presenceRequest.getPresence();
        Element frag = presence.getChildElement("x", "http://jabber.org/protocol/muc#user");
        // Don't include the occupant's JID if the room is semi-anon and the new occupant
        // is not a moderator
        if (!canAnyoneDiscoverJID()) {
            jid = frag.element("item").attributeValue("jid");
        }
        for (MUCRole occupant : occupantsByFullJID.values()) {
            if (!occupant.isLocal()) {
                continue;
            }
            // Don't include the occupant's JID if the room is semi-anon and the new occupant
            // is not a moderator
            if (!canAnyoneDiscoverJID()) {
                if (MUCRole.Role.moderator == occupant.getRole()) {
                    frag.element("item").addAttribute("jid", jid);
                }
                else {
                    frag.element("item").addAttribute("jid", null);
                }
            }
            // Some status codes should only be included in the "self-presence", which is only sent to the user, but not to other occupants.
            if (occupant.getPresence().getFrom().equals(presence.getTo())) {
                Presence selfPresence = presence.createCopy();
                Element fragSelfPresence = selfPresence.getChildElement("x", "http://jabber.org/protocol/muc#user");
                fragSelfPresence.addElement("status").addAttribute("code", "110");

                // Only in the context of entering the room status code 100, 201 and 210 should be sent.
                // http://xmpp.org/registrar/mucstatus.html
                if (presenceRequest.isJoinPresence()) {
                    boolean isRoomNew = isLocked() && creationDate.getTime() == lockedTime;
                    if (canAnyoneDiscoverJID()) {
                        // // XEP-0045: Example 26.
                        // If the user is entering a room that is non-anonymous (i.e., which informs all occupants of each occupant's full JID as shown above), the service MUST warn the user by including a status code of "100" in the initial presence that the room sends to the new occupant
                        fragSelfPresence.addElement("status").addAttribute("code", "100");
                    }
                    if (isRoomNew) {
                        fragSelfPresence.addElement("status").addAttribute("code", "201");
                    }
                }

                occupant.send(selfPresence);
            } else {
                occupant.send(presence);
            }
        }
    }

    private void broadcast(Message message) {
        // Broadcast message to occupants hosted by other cluster nodes
        BroadcastMessageRequest request = new BroadcastMessageRequest(this, message, occupantsByFullJID.size());
        CacheFactory.doClusterTask(request);

        // Broadcast message to occupants connected to this JVM
        request = new BroadcastMessageRequest(this, message, occupantsByFullJID.size());
        request.setOriginator(true);
        request.run();
    }

    public void broadcast(BroadcastMessageRequest messageRequest) {
        Message message = messageRequest.getMessage();
        // Add message to the room history
        roomHistory.addMessage(message);
        // Send message to occupants connected to this JVM
        for (MUCRole occupant : occupantsByFullJID.values()) {
            // Do not send broadcast messages to deaf occupants or occupants hosted in
            // other cluster nodes
            if (occupant.isLocal() && !occupant.isVoiceOnly()) {
                occupant.send(message);
            }
        }
        if (messageRequest.isOriginator() && isLogEnabled()) {
            MUCRole senderRole = null;
            JID senderAddress;
            // convert the MUC nickname/role JID back into a real user JID
            if (message.getFrom() != null && message.getFrom().getResource() != null) {
            	// get the first MUCRole for the sender
            	List<MUCRole> occupants = occupantsByNickname.get(message.getFrom().getResource().toLowerCase());
                senderRole = occupants == null ? null : occupants.get(0);
            }
            if (senderRole == null) {
                // The room itself is sending the message
                senderAddress = getRole().getRoleAddress();
            }
            else {
                // An occupant is sending the message
                senderAddress = senderRole.getUserAddress();
            }
            // Log the conversation
            mucService.logConversation(this, message, senderAddress);
        }
        mucService.messageBroadcastedTo(messageRequest.getOccupants());
    }

    /**
     * An empty role that represents the room itself in the chatroom. Chatrooms need to be able to
     * speak (server messages) and so must have their own role in the chatroom.
     */
    private class RoomRole implements MUCRole {

        private MUCRoom room;

        private RoomRole(MUCRoom room) {
            this.room = room;
        }

        public Presence getPresence() {
            return null;
        }

        public void setPresence(Presence presence) {
        }

        public void setRole(MUCRole.Role newRole) {
        }

        public MUCRole.Role getRole() {
            return MUCRole.Role.moderator;
        }

        public void setAffiliation(MUCRole.Affiliation newAffiliation) {
        }

        public MUCRole.Affiliation getAffiliation() {
            return MUCRole.Affiliation.owner;
        }

        public void changeNickname(String nickname) {
        }

        public String getNickname() {
            return null;
        }

        public boolean isVoiceOnly() {
            return false;
        }

        public boolean isLocal() {
            return true;
        }

        public NodeID getNodeID() {
            return XMPPServer.getInstance().getNodeID();
        }

        public MUCRoom getChatRoom() {
            return room;
        }

        private JID crJID = null;

        public JID getRoleAddress() {
            if (crJID == null) {
                crJID = new JID(room.getName(), mucService.getServiceDomain(), null, true);
            }
            return crJID;
        }

        public JID getUserAddress() {
            return null;
        }

        public void send(Packet packet) {
            room.send(packet);
        }

        public void destroy() {
        }
    }

    public long getChatLength() {
        return endTime - startTime;
    }

    /**
     * Updates all the presences of the given user with the new affiliation and role information. Do
     * nothing if the given jid is not present in the room. If the user has joined the room from
     * several client resources, all his/her occupants' presences will be updated.
     *
     * @param jid the bare jid of the user to update his/her role.
     * @param newAffiliation the new affiliation for the JID.
     * @param newRole the new role for the JID.
     * @return the list of updated presences of all the client resources that the client used to
     *         join the room.
     * @throws NotAllowedException If trying to change the moderator role to an owner or an admin or
     *         if trying to ban an owner or an administrator.
     */
    private List<Presence> changeOccupantAffiliation(JID jid, MUCRole.Affiliation newAffiliation, MUCRole.Role newRole)
            throws NotAllowedException {
        List<Presence> presences = new ArrayList<Presence>();
        // Get all the roles (i.e. occupants) of this user based on his/her bare JID
        JID bareJID = jid.asBareJID();
		List<MUCRole> roles = occupantsByBareJID.get(bareJID);
        if (roles == null) {
            return presences;
        }
        // Collect all the updated presences of these roles
        for (MUCRole role : roles) {
            // Update the presence with the new affiliation and role
            if (role.isLocal()) {
                role.setAffiliation(newAffiliation);
                role.setRole(newRole);
                // Notify the other cluster nodes to update the occupant
                CacheFactory.doClusterTask(new UpdateOccupant(this, role));
                // Prepare a new presence to be sent to all the room occupants
                presences.add(role.getPresence().createCopy());
            }
            else {
                // Ask the cluster node hosting the occupant to make the changes. Note that if the change
                // is not allowed a NotAllowedException will be thrown
                Element element = (Element) CacheFactory.doSynchronousClusterTask(
                        new UpdateOccupantRequest(this, role.getNickname(), newAffiliation, newRole),
                        role.getNodeID().toByteArray());
                if (element != null) {
                    // Prepare a new presence to be sent to all the room occupants
                    presences.add(new Presence(element, true));
                }
                else {
                    throw new NotAllowedException();
                }
            }
        }
        // Answer all the updated presences
        return presences;
    }

    /**
     * Updates the presence of the given user with the new role information. Do nothing if the given
     * jid is not present in the room.
     *
     * @param jid the full jid of the user to update his/her role.
     * @param newRole the new role for the JID.
     * @return the updated presence of the user or null if none.
     * @throws NotAllowedException If trying to change the moderator role to an owner or an admin.
     */
    private Presence changeOccupantRole(JID jid, MUCRole.Role newRole) throws NotAllowedException {
        // Try looking the role in the bare JID list
        MUCRole role = occupantsByFullJID.get(jid);
        if (role != null) {
            if (role.isLocal()) {
                // Update the presence with the new role
                role.setRole(newRole);
                // Notify the other cluster nodes to update the occupant
                CacheFactory.doClusterTask(new UpdateOccupant(this, role));
                // Prepare a new presence to be sent to all the room occupants
                return role.getPresence().createCopy();
            }
            else {
                // Ask the cluster node hosting the occupant to make the changes. Note that if the change
                // is not allowed a NotAllowedException will be thrown
                Element element = (Element) CacheFactory.doSynchronousClusterTask(
                        new UpdateOccupantRequest(this, role.getNickname(), null, newRole),
                        role.getNodeID().toByteArray());
                if (element != null) {
                    // Prepare a new presence to be sent to all the room occupants
                    return new Presence(element, true);
                }
                else {
                    throw new NotAllowedException();
                }
            }
        }
        return null;
    }

    public void addFirstOwner(JID bareJID) {
        owners.add( bareJID.asBareJID() );
    }

    public List<Presence> addOwner(JID jid, MUCRole sendRole) throws ForbiddenException {
        final JID bareJID = jid.asBareJID();
        lock.writeLock().lock();
        try {
            MUCRole.Affiliation oldAffiliation = MUCRole.Affiliation.none;
            if (MUCRole.Affiliation.owner != sendRole.getAffiliation()) {
                throw new ForbiddenException();
            }
            // Check if user is already an owner
			if (owners.contains(bareJID)) {
                // Do nothing
                return Collections.emptyList();
            }
            owners.add(bareJID);
            // Remove the user from other affiliation lists
            if (removeAdmin(bareJID)) {
                oldAffiliation = MUCRole.Affiliation.admin;
            }
            else if (removeMember(bareJID)) {
                oldAffiliation = MUCRole.Affiliation.member;
            }
            else if (removeOutcast(bareJID)) {
                oldAffiliation = MUCRole.Affiliation.outcast;
            }
            // Update the DB if the room is persistent
            MUCPersistenceManager.saveAffiliationToDB(
                this,
                bareJID,
                null,
                MUCRole.Affiliation.owner,
                oldAffiliation);
        }
        finally {
            lock.writeLock().unlock();
        }
        // Update other cluster nodes with new affiliation
        CacheFactory.doClusterTask(new AddAffiliation(this, jid.toBareJID(), MUCRole.Affiliation.owner));
        // Update the presence with the new affiliation and inform all occupants
        try {
            return changeOccupantAffiliation(jid, MUCRole.Affiliation.owner,
                    MUCRole.Role.moderator);
        }
        catch (NotAllowedException e) {
            // We should never receive this exception....in theory
            return null;
        }
    }

    private boolean removeOwner(JID jid) {
        return owners.remove(jid.asBareJID());
    }

    public List<Presence> addAdmin(JID jid, MUCRole sendRole) throws ForbiddenException,
            ConflictException {
    	final JID bareJID = jid.asBareJID();
        lock.writeLock().lock();
        try {
            MUCRole.Affiliation oldAffiliation = MUCRole.Affiliation.none;
            if (MUCRole.Affiliation.owner != sendRole.getAffiliation()) {
                throw new ForbiddenException();
            }
            // Check that the room always has an owner
            if (owners.contains(bareJID) && owners.size() == 1) {
                throw new ConflictException();
            }
            // Check if user is already an admin
            if (admins.contains(bareJID)) {
                // Do nothing
                return Collections.emptyList();
            }
            admins.add(bareJID);
            // Remove the user from other affiliation lists
            if (removeOwner(bareJID)) {
                oldAffiliation = MUCRole.Affiliation.owner;
            }
            else if (removeMember(bareJID)) {
                oldAffiliation = MUCRole.Affiliation.member;
            }
            else if (removeOutcast(bareJID)) {
                oldAffiliation = MUCRole.Affiliation.outcast;
            }
            // Update the DB if the room is persistent
            MUCPersistenceManager.saveAffiliationToDB(
                this,
                bareJID,
                null,
                MUCRole.Affiliation.admin,
                oldAffiliation);
        }
        finally {
            lock.writeLock().unlock();
        }
        // Update other cluster nodes with new affiliation
        CacheFactory.doClusterTask(new AddAffiliation(this, jid.toBareJID(), MUCRole.Affiliation.admin));
        // Update the presence with the new affiliation and inform all occupants
        try {
            return changeOccupantAffiliation(jid, MUCRole.Affiliation.admin,
                    MUCRole.Role.moderator);
        }
        catch (NotAllowedException e) {
            // We should never receive this exception....in theory
            return null;
        }
    }

    private boolean removeAdmin(JID bareJID) {
        return admins.remove( bareJID.asBareJID() );
    }

    public List<Presence> addMember(JID jid, String nickname, MUCRole sendRole)
            throws ForbiddenException, ConflictException {
    	final JID bareJID = jid.asBareJID();
        lock.writeLock().lock();
        try {
            MUCRole.Affiliation oldAffiliation = (members.containsKey(bareJID) ?
                    MUCRole.Affiliation.member : MUCRole.Affiliation.none);
            if (isMembersOnly()) {
                if (!canOccupantsInvite()) {
                    if (MUCRole.Affiliation.admin != sendRole.getAffiliation()
                            && MUCRole.Affiliation.owner != sendRole.getAffiliation()) {
                        throw new ForbiddenException();
                    }
                }
            }
            else {
                if (MUCRole.Affiliation.admin != sendRole.getAffiliation()
                        && MUCRole.Affiliation.owner != sendRole.getAffiliation()) {
                    throw new ForbiddenException();
                }
            }
            // DEBUG check to make sure tables are getting cleaned up
            if (members.size() > 1000) {
                Log.error("Room " + getName() + " has too many members " + members.size() );
            }
            // Check if the desired nickname is already reserved for another member
            if (nickname != null && nickname.trim().length() > 0 && members.containsValue(nickname.toLowerCase())) {
                if (!nickname.equals(members.get(bareJID))) {
                    throw new ConflictException();
                }
            }
            // Check that the room always has an owner
            if (owners.contains(bareJID) && owners.size() == 1) {
                throw new ConflictException();
            }
            // Associate the reserved nickname with the bareJID. If nickname is null then associate an
            // empty string
            members.put(bareJID, (nickname == null ? "" : nickname.toLowerCase()));
            // Remove the user from other affiliation lists
            if (removeOwner(bareJID)) {
                oldAffiliation = MUCRole.Affiliation.owner;
            }
            else if (removeAdmin(bareJID)) {
                oldAffiliation = MUCRole.Affiliation.admin;
            }
            else if (removeOutcast(bareJID)) {
                oldAffiliation = MUCRole.Affiliation.outcast;
            }
            // Update the DB if the room is persistent
            MUCPersistenceManager.saveAffiliationToDB(
                this,
                bareJID,
                nickname,
                MUCRole.Affiliation.member,
                oldAffiliation);
        }
        finally {
            lock.writeLock().unlock();
        }
        // Update other cluster nodes with new member
        CacheFactory.doClusterTask(new AddMember(this, jid.toBareJID(), (nickname == null ? "" : nickname)));
        // Update the presence with the new affiliation and inform all occupants
        try {
            return changeOccupantAffiliation(jid, MUCRole.Affiliation.member,
                    MUCRole.Role.participant);
        }
        catch (NotAllowedException e) {
            // We should never receive this exception....in theory
            return null;
        }
    }

    private boolean removeMember(JID jid) {
        return members.remove(jid.asBareJID()) != null;
    }

    public List<Presence> addOutcast(JID jid, String reason, MUCRole senderRole)
            throws NotAllowedException, ForbiddenException, ConflictException {
        final JID bareJID = jid.asBareJID();
        lock.writeLock().lock();
        try {
            MUCRole.Affiliation oldAffiliation = MUCRole.Affiliation.none;
            if (MUCRole.Affiliation.admin != senderRole.getAffiliation()
                    && MUCRole.Affiliation.owner != senderRole.getAffiliation()) {
                throw new ForbiddenException();
            }
            // Check that the room always has an owner
            if (owners.contains(bareJID) && owners.size() == 1) {
                throw new ConflictException();
            }
            // Check if user is already an outcast
            if (outcasts.contains(bareJID)) {
                // Do nothing
                return Collections.emptyList();
            }

            // Update the affiliation lists
            outcasts.add(bareJID);
            // Remove the user from other affiliation lists
            if (removeOwner(bareJID)) {
                oldAffiliation = MUCRole.Affiliation.owner;
            }
            else if (removeAdmin(bareJID)) {
                oldAffiliation = MUCRole.Affiliation.admin;
            }
            else if (removeMember(bareJID)) {
                oldAffiliation = MUCRole.Affiliation.member;
            }
            // Update the DB if the room is persistent
            MUCPersistenceManager.saveAffiliationToDB(
                this,
                bareJID,
                null,
                MUCRole.Affiliation.outcast,
                oldAffiliation);
        }
        finally {
            lock.writeLock().unlock();
        }
        // Update other cluster nodes with new affiliation
        CacheFactory.doClusterTask(new AddAffiliation(this, jid.toBareJID(), MUCRole.Affiliation.outcast));
        // Update the presence with the new affiliation and inform all occupants
        // actorJID will be null if the room itself (ie. via admin console) made the request
        JID actorJID = senderRole.getUserAddress();
        List<Presence> updatedPresences = changeOccupantAffiliation(
                jid,
                MUCRole.Affiliation.outcast,
                MUCRole.Role.none);
        Element frag;
        // Add the status code and reason why the user was banned to the presences that will
        // be sent to the room occupants (the banned user will not receive this presences)
        for (Presence presence : updatedPresences) {
            frag = presence.getChildElement("x", "http://jabber.org/protocol/muc#user");
            // Add the status code 301 that indicates that the user was banned
            frag.addElement("status").addAttribute("code", "301");
            // Add the reason why the user was banned
            if (reason != null && reason.trim().length() > 0) {
                frag.element("item").addElement("reason").setText(reason);
            }

            // Remove the banned users from the room. If a user has joined the room from
            // different client resources, he/she will be kicked from all the client resources
            // Effectively kick the occupant from the room
            kickPresence(presence, actorJID);
        }
        return updatedPresences;
    }

    private boolean removeOutcast(JID bareJID) {
        return outcasts.remove( bareJID.asBareJID() );
    }

    public List<Presence> addNone(JID jid, MUCRole senderRole) throws ForbiddenException,
            ConflictException {
    	final JID bareJID = jid.asBareJID();
        List<Presence> updatedPresences = Collections.emptyList();
        boolean wasMember = false;
        lock.writeLock().lock();
        try {
            MUCRole.Affiliation oldAffiliation = MUCRole.Affiliation.none;
            if (MUCRole.Affiliation.admin != senderRole.getAffiliation()
                    && MUCRole.Affiliation.owner != senderRole.getAffiliation()) {
                throw new ForbiddenException();
            }
            // Check that the room always has an owner
            if (owners.contains(bareJID) && owners.size() == 1) {
                throw new ConflictException();
            }
            wasMember = members.containsKey(bareJID) || admins.contains(bareJID) || owners.contains(bareJID);
            // Remove the user from ALL the affiliation lists
            if (removeOwner(bareJID)) {
                oldAffiliation = MUCRole.Affiliation.owner;
            }
            else if (removeAdmin(bareJID)) {
                oldAffiliation = MUCRole.Affiliation.admin;
            }
            else if (removeMember(bareJID)) {
                oldAffiliation = MUCRole.Affiliation.member;
            }
            else if (removeOutcast(bareJID)) {
                oldAffiliation = MUCRole.Affiliation.outcast;
            }
            // Remove the affiliation of this user from the DB if the room is persistent
            MUCPersistenceManager.removeAffiliationFromDB(this, bareJID, oldAffiliation);
        }
        finally {
            lock.writeLock().unlock();
        }
        // Update other cluster nodes with new affiliation
        CacheFactory.doClusterTask(new AddAffiliation(this, jid.toBareJID(), MUCRole.Affiliation.none));
        // Update the presence with the new affiliation and inform all occupants
        try {
            MUCRole.Role newRole;
            if (isMembersOnly() && wasMember) {
                newRole = MUCRole.Role.none;
            }
            else {
                newRole = isModerated() ? MUCRole.Role.visitor : MUCRole.Role.participant;
            }
            updatedPresences = changeOccupantAffiliation(bareJID, MUCRole.Affiliation.none, newRole);
            if (isMembersOnly() && wasMember) {
                // If the room is members-only, remove the user from the room including a status
                // code of 321 to indicate that the user was removed because of an affiliation
                // change
                Element frag;
                // Add the status code to the presences that will be sent to the room occupants
                for (Presence presence : updatedPresences) {
                    // Set the presence as an unavailable presence
                    presence.setType(Presence.Type.unavailable);
                    presence.setStatus(null);
                    frag = presence.getChildElement("x", "http://jabber.org/protocol/muc#user");
                    // Add the status code 321 that indicates that the user was removed because of
                    // an affiliation change
                    frag.addElement("status").addAttribute("code", "321");

                    // Remove the ex-member from the room. If a user has joined the room from
                    // different client resources, he/she will be kicked from all the client
                    // resources.
                    // Effectively kick the occupant from the room
                    JID actorJID = senderRole.getUserAddress();
                    kickPresence(presence, actorJID);
                }
            }
        }
        catch (NotAllowedException e) {
            // We should never receive this exception....in theory
        	Log.error(e.getMessage(), e);
        }
        return updatedPresences;
    }

    public boolean isLocked() {
        return lockedTime > 0;
    }

    public boolean isManuallyLocked() {
        return lockedTime > 0 && creationDate.getTime() != lockedTime;
    }

    public void presenceUpdated(MUCRole occupantRole, Presence newPresence) {
        // Ask other cluster nodes to update the presence of the occupant
        UpdatePresence request = new UpdatePresence(this, newPresence.createCopy(), occupantRole.getNickname());
        CacheFactory.doClusterTask(request);

        // Update the presence of the occupant
        request = new UpdatePresence(this, newPresence.createCopy(), occupantRole.getNickname());
        request.setOriginator(true);
        request.run();

        // Broadcast new presence of occupant
        broadcastPresence(occupantRole.getPresence().createCopy(), false);
    }

    /**
     * Updates the presence of an occupant with the new presence included in the request.
     *
     * @param updatePresence request to update an occupant's presence.
     */
    public void presenceUpdated(UpdatePresence updatePresence) {
    	List <MUCRole> occupants = occupantsByNickname.get(updatePresence.getNickname().toLowerCase());
    	if (occupants == null || occupants.size() == 0) {
            Log.debug("LocalMUCRoom: Failed to update presence of room occupant. Occupant nickname: " + updatePresence.getNickname());
    	} else {
            for (MUCRole occupant : occupants) {
                occupant.setPresence(updatePresence.getPresence());
            }
    	}
    }

    public void occupantUpdated(UpdateOccupant update) {
    	List <MUCRole> occupants = occupantsByNickname.get(update.getNickname().toLowerCase());
    	if (occupants == null || occupants.size() == 0) {
            Log.debug("LocalMUCRoom: Failed to update information of room occupant. Occupant nickname: " + update.getNickname());
    	} else {
            for (MUCRole occupant : occupants) {
                if (!occupant.isLocal()) {
                    occupant.setPresence(update.getPresence());
                    try {
                        occupant.setRole(update.getRole());
                        occupant.setAffiliation(update.getAffiliation());
                    } catch (NotAllowedException e) {
                        // Ignore. Should never happen with remote roles
                    }
                }
                else {
                    Log.error(MessageFormat.format("Ignoring update of local occupant with info from a remote occupant. "
                    		+ "Occupant nickname: {0} new role: {1} new affiliation: {2}", 
                    		update.getNickname(), update.getRole(), update.getAffiliation()));
                }
            }
    	}
    }

    public Presence updateOccupant(UpdateOccupantRequest updateRequest) throws NotAllowedException {
    	Presence result = null;
    	List <MUCRole> occupants = occupantsByNickname.get(updateRequest.getNickname().toLowerCase());
    	if (occupants == null || occupants.size() == 0) {
            Log.debug("Failed to update information of local room occupant; nickname: " + updateRequest.getNickname());
    	} else {
    		for (MUCRole occupant : occupants) {
                if (updateRequest.isAffiliationChanged()) {
                	occupant.setAffiliation(updateRequest.getAffiliation());
                }
                occupant.setRole(updateRequest.getRole());
                // Notify the the cluster nodes to update the occupant
                CacheFactory.doClusterTask(new UpdateOccupant(this, occupant));
                if (result == null) {
                	result = occupant.getPresence();
                }
    		}
    	}
        return result;
    }

    public void memberAdded(AddMember addMember) {
    	JID bareJID = addMember.getBareJID();
    	removeOwner(bareJID);
    	removeAdmin(bareJID);
    	removeOutcast(bareJID);
        // Associate the reserved nickname with the bareJID
        members.put(addMember.getBareJID(), addMember.getNickname().toLowerCase());
    }

    public void affiliationAdded(AddAffiliation affiliation) {
    	JID bareJID = affiliation.getBareJID();
        switch(affiliation.getAffiliation()) {
	        case owner:
	        	removeMember(bareJID);
	        	removeAdmin(bareJID);
	        	removeOutcast(bareJID);
	        	owners.add(bareJID);
	        	break;
	        case admin:
	        	removeMember(bareJID);
	        	removeOwner(bareJID);
	        	removeOutcast(bareJID);
	        	admins.add(bareJID);
	        	break;
	        case outcast:
	        	removeMember(bareJID);
	        	removeAdmin(bareJID);
	        	removeOwner(bareJID);
	        	outcasts.add(bareJID);
	        	break;
	        case none:
	        default:
	        	removeMember(bareJID);
	        	removeAdmin(bareJID);
	        	removeOwner(bareJID);
	        	removeOutcast(bareJID);
	        	break;
        }
    }

    public void nicknameChanged(MUCRole occupantRole, Presence newPresence, String oldNick, String newNick) {
        // Ask other cluster nodes to update the nickname of the occupant
        ChangeNickname request = new ChangeNickname(this, oldNick,  newNick, newPresence.createCopy());
        CacheFactory.doClusterTask(request);

        // Update the nickname of the occupant
        request = new ChangeNickname(this, oldNick,  newNick, newPresence.createCopy());
        request.setOriginator(true);
        request.run();

        // Broadcast new presence of occupant
        broadcastPresence(occupantRole.getPresence().createCopy(), false);
    }

    public void nicknameChanged(ChangeNickname changeNickname) {
    	List<MUCRole> occupants = occupantsByNickname.get(changeNickname.getOldNick().toLowerCase());
    	if (occupants != null && occupants.size() > 0) {
	    	for (MUCRole occupant : occupants) {
	            // Update the role with the new info
	    		occupant.setPresence(changeNickname.getPresence());
	    		occupant.changeNickname(changeNickname.getNewNick());
	    	}
	        if (changeNickname.isOriginator()) {
	            // Fire event that user changed his nickname
	            MUCEventDispatcher.nicknameChanged(getRole().getRoleAddress(), occupants.get(0).getUserAddress(),
	                    changeNickname.getOldNick(), changeNickname.getNewNick());
	        }
	        // Associate the existing MUCRole with the new nickname
	        occupantsByNickname.put(changeNickname.getNewNick().toLowerCase(), occupants);
	        // Remove the old nickname
	        occupantsByNickname.remove(changeNickname.getOldNick().toLowerCase());
    	}
    }

    public void changeSubject(Message packet, MUCRole role) throws ForbiddenException {
        if ((canOccupantsChangeSubject() && role.getRole().compareTo(MUCRole.Role.visitor) < 0) ||
                MUCRole.Role.moderator == role.getRole()) {
            // Do nothing if the new subject is the same as the existing one
            if (packet.getSubject().equals(subject)) {
                return;
            }
            // Set the new subject to the room
            subject = packet.getSubject();
            MUCPersistenceManager.updateRoomSubject(this);
            // Notify all the occupants that the subject has changed
            packet.setFrom(role.getRoleAddress());
            send(packet);

            // Fire event signifying that the room's subject has changed.
            MUCEventDispatcher.roomSubjectChanged(getJID(), role.getUserAddress(), subject);

            if (!"local-only".equals(packet.getID())) {
	            // Let other cluster nodes that the room has been updated
	            CacheFactory.doClusterTask(new RoomUpdatedEvent(this));
            }
        }
        else {
            throw new ForbiddenException();
        }
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public void sendInvitation(JID to, String reason, MUCRole senderRole, List<Element> extensions)
            throws ForbiddenException, CannotBeInvitedException {
        if (!isMembersOnly() || canOccupantsInvite()
                || MUCRole.Affiliation.admin == senderRole.getAffiliation()
                || MUCRole.Affiliation.owner == senderRole.getAffiliation()) {
            // If the room is not members-only OR if the room is members-only and anyone can send
            // invitations or the sender is an admin or an owner, then send the invitation
            Message message = new Message();
            message.setFrom(role.getRoleAddress());
            message.setTo(to);

            if (((MultiUserChatServiceImpl)mucService).getMUCDelegate() != null) {
                switch(((MultiUserChatServiceImpl)mucService).getMUCDelegate().sendingInvitation(this, to, senderRole.getUserAddress(), reason)) {
                    case HANDLED_BY_DELEGATE:
                        //if the delegate is taking care of it, there's nothing for us to do
                        return;
                    case HANDLED_BY_OPENFIRE:
                        //continue as normal if we're asked to handle it
                        break;
                    case REJECTED:
                        //we can't invite that person
                        throw new CannotBeInvitedException();
                }
            }

            // Add a list of extensions sent with the original message invitation (if any)
            if (extensions != null) {
                for(Element element : extensions) {
                    element.setParent(null);
                    message.getElement().add(element);
                }
            }
            Element frag = message.addChildElement("x", "http://jabber.org/protocol/muc#user");
            // ChatUser will be null if the room itself (ie. via admin console) made the request
            if (senderRole.getUserAddress() != null) {
                frag.addElement("invite").addAttribute("from", senderRole.getUserAddress().toBareJID());
            }
            if (reason != null && reason.length() > 0) {
                Element invite = frag.element("invite");
                if (invite == null) {
                    invite = frag.addElement("invite");
                }
                invite.addElement("reason").setText(reason);
            }
            if (isPasswordProtected()) {
                frag.addElement("password").setText(getPassword());
            }

            // Include the jabber:x:conference information for backward compatibility
            frag = message.addChildElement("x", "jabber:x:conference");
            frag.addAttribute("jid", role.getRoleAddress().toBareJID());

            // Send the message with the invitation
            router.route(message);
        }
        else {
            throw new ForbiddenException();
        }
    }

    public void sendInvitationRejection(JID to, String reason, JID sender) {
        Message message = new Message();
        message.setFrom(role.getRoleAddress());
        message.setTo(to);
        Element frag = message.addChildElement("x", "http://jabber.org/protocol/muc#user");
        frag.addElement("decline").addAttribute("from", sender.toBareJID());
        if (reason != null && reason.length() > 0) {
            frag.element("decline").addElement("reason").setText(reason);
        }

        // Send the message with the invitation
        router.route(message);
    }

    public IQOwnerHandler getIQOwnerHandler() {
        return iqOwnerHandler;
    }

    public IQAdminHandler getIQAdminHandler() {
        return iqAdminHandler;
    }

    public MUCRoomHistory getRoomHistory() {
        return roomHistory;
    }

    public Collection<JID> getOwners() {
        return Collections.unmodifiableList(owners);
    }

    public Collection<JID> getAdmins() {
        return Collections.unmodifiableList(admins);
    }

    public Collection<JID> getMembers() {
        return Collections.unmodifiableMap(members).keySet();
    }

    public Collection<JID> getOutcasts() {
        return Collections.unmodifiableList(outcasts);
    }

    public Collection<MUCRole> getModerators() {
        List<MUCRole> moderators = new ArrayList<MUCRole>();
        for (MUCRole role : occupantsByFullJID.values()) {
            if (MUCRole.Role.moderator == role.getRole()) {
                moderators.add(role);
            }
        }
        return moderators;
    }

    public Collection<MUCRole> getParticipants() {
        List<MUCRole> participants = new ArrayList<MUCRole>();
        for (MUCRole role : occupantsByFullJID.values()) {
            if (MUCRole.Role.participant == role.getRole()) {
                participants.add(role);
            }
        }
        return participants;
    }

    public Presence addModerator(JID jid, MUCRole senderRole) throws ForbiddenException {
        if (MUCRole.Affiliation.admin != senderRole.getAffiliation()
                && MUCRole.Affiliation.owner != senderRole.getAffiliation()) {
            throw new ForbiddenException();
        }
        // Update the presence with the new role and inform all occupants
        try {
            return changeOccupantRole(jid, MUCRole.Role.moderator);
        }
        catch (NotAllowedException e) {
            // We should never receive this exception....in theory
            return null;
        }
    }

    public Presence addParticipant(JID jid, String reason, MUCRole senderRole)
            throws NotAllowedException, ForbiddenException {
        if (MUCRole.Role.moderator != senderRole.getRole()) {
            throw new ForbiddenException();
        }
        // Update the presence with the new role and inform all occupants
        Presence updatedPresence = changeOccupantRole(jid, MUCRole.Role.participant);
        if (updatedPresence != null) {
            Element frag = updatedPresence.getChildElement(
                    "x", "http://jabber.org/protocol/muc#user");
            // Add the reason why the user was granted voice
            if (reason != null && reason.trim().length() > 0) {
                frag.element("item").addElement("reason").setText(reason);
            }
        }
        return updatedPresence;
    }

    public Presence addVisitor(JID jid, MUCRole senderRole) throws NotAllowedException,
            ForbiddenException {
        if (MUCRole.Role.moderator != senderRole.getRole()) {
            throw new ForbiddenException();
        }
        return changeOccupantRole(jid, MUCRole.Role.visitor);
    }

    public Presence kickOccupant(JID jid, JID actorJID, String reason)
            throws NotAllowedException {
        // Update the presence with the new role and inform all occupants
        Presence updatedPresence = changeOccupantRole(jid, MUCRole.Role.none);
        if (updatedPresence != null) {
            Element frag = updatedPresence.getChildElement(
                    "x", "http://jabber.org/protocol/muc#user");

            // Add the status code 307 that indicates that the user was kicked
            frag.addElement("status").addAttribute("code", "307");
            // Add the reason why the user was kicked
            if (reason != null && reason.trim().length() > 0) {
                frag.element("item").addElement("reason").setText(reason);
            }

            // Effectively kick the occupant from the room
            kickPresence(updatedPresence, actorJID);
            
            //Inform the other occupants that user has been kicked
            broadcastPresence(updatedPresence, false);
        }
        return updatedPresence;
    }

    /**
     * Kicks the occupant from the room. This means that the occupant will receive an unavailable
     * presence with the actor that initiated the kick (if any). The occupant will also be removed
     * from the occupants lists.
     *
     * @param kickPresence the presence of the occupant to kick from the room.
     * @param actorJID The JID of the actor that initiated the kick or <tt>null</tt> if the info
     * was not provided.
     */
    private void kickPresence(Presence kickPresence, JID actorJID) {
        // Get the role(s) to kick
        List<MUCRole> occupants = occupantsByNickname.get(kickPresence.getFrom().getResource().toLowerCase());
        for (MUCRole kickedRole : occupants) {
            kickPresence = kickPresence.createCopy();
            // Add the actor's JID that kicked this user from the room
            if (actorJID != null && actorJID.toString().length() > 0) {
                Element frag = kickPresence.getChildElement(
                        "x", "http://jabber.org/protocol/muc#user");
                frag.element("item").addElement("actor").addAttribute("jid", actorJID.toBareJID());
            }
            // Send the unavailable presence to the banned user
            kickedRole.send(kickPresence);
            // Remove the occupant from the room's occupants lists
            OccupantLeftEvent event = new OccupantLeftEvent(this, kickedRole);
            event.setOriginator(true);
            event.run();

            // Remove the occupant from the room's occupants lists
            event = new OccupantLeftEvent(this, kickedRole);
            CacheFactory.doClusterTask(event);
        }
    }

    public boolean canAnyoneDiscoverJID() {
        return canAnyoneDiscoverJID;
    }

    public void setCanAnyoneDiscoverJID(boolean canAnyoneDiscoverJID) {
        this.canAnyoneDiscoverJID = canAnyoneDiscoverJID;
    }

    public boolean canOccupantsChangeSubject() {
        return canOccupantsChangeSubject;
    }

    public void setCanOccupantsChangeSubject(boolean canOccupantsChangeSubject) {
        this.canOccupantsChangeSubject = canOccupantsChangeSubject;
    }

    public boolean canOccupantsInvite() {
        return canOccupantsInvite;
    }

    public void setCanOccupantsInvite(boolean canOccupantsInvite) {
        this.canOccupantsInvite = canOccupantsInvite;
    }

    public String getNaturalLanguageName() {
        return naturalLanguageName;
    }

    public void setNaturalLanguageName(String naturalLanguageName) {
        this.naturalLanguageName = naturalLanguageName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isMembersOnly() {
        return membersOnly;
    }

    public List<Presence> setMembersOnly(boolean membersOnly) {
        List<Presence> presences = new ArrayList<Presence>();
        if (membersOnly && !this.membersOnly) {
            // If the room was not members-only and now it is, kick occupants that aren't member
            // of the room
            for (MUCRole occupant : occupantsByFullJID.values()) {
                if (occupant.getAffiliation().compareTo(MUCRole.Affiliation.member) > 0) {
                    try {
                        presences.add(kickOccupant(occupant.getRoleAddress(), null,
                                LocaleUtils.getLocalizedString("muc.roomIsNowMembersOnly")));
                    }
                    catch (NotAllowedException e) {
                        Log.error(e.getMessage(), e);
                    }
                }
            }
        }
        this.membersOnly = membersOnly;
        return presences;
    }

    public boolean isLogEnabled() {
        return logEnabled;
    }

    public void setLogEnabled(boolean logEnabled) {
        this.logEnabled = logEnabled;
    }

    public void setLoginRestrictedToNickname(boolean restricted) {
        this.loginRestrictedToNickname = restricted;
    }

    public boolean isLoginRestrictedToNickname() {
        return loginRestrictedToNickname;
    }

    public void setChangeNickname(boolean canChange) {
        this.canChangeNickname = canChange;
    }

    public boolean canChangeNickname() {
        return canChangeNickname;
    }

    public void setRegistrationEnabled(boolean registrationEnabled) {
        this.registrationEnabled = registrationEnabled;
    }

    public boolean isRegistrationEnabled() {
        return registrationEnabled;
    }

    public int getMaxUsers() {
        return maxUsers;
    }

    public void setMaxUsers(int maxUsers) {
        this.maxUsers = maxUsers;
    }

    public boolean isModerated() {
        return moderated;
    }

    public void setModerated(boolean moderated) {
        this.moderated = moderated;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isPasswordProtected() {
        return password != null && password.trim().length() > 0;
    }

    public boolean isPersistent() {
        return persistent;
    }

    public boolean wasSavedToDB() {
        return isPersistent() && savedToDB;
    }

    public void setSavedToDB(boolean saved) {
        this.savedToDB = saved;
    }

    public void setPersistent(boolean persistent) {
        this.persistent = persistent;
    }

    public boolean isPublicRoom() {
        return !isDestroyed && publicRoom;
    }

    public void setPublicRoom(boolean publicRoom) {
        this.publicRoom = publicRoom;
    }

    public boolean blockInitialPresence() {
        return blockInitialPresence;
    }

    public void setBlockInitialPresence(boolean block) {
        this.blockInitialPresence = block;
    }

    public List<String> getRolesToBroadcastPresence() {
        return Collections.unmodifiableList(rolesToBroadcastPresence);
    }

    public void setRolesToBroadcastPresence(List<String> rolesToBroadcastPresence) {
        // TODO If the list changes while there are occupants in the room we must send available or
        // unavailable presences of the affected occupants to the rest of the occupants
        this.rolesToBroadcastPresence = rolesToBroadcastPresence;
    }

    /**
     * Returns true if we need to check whether a presence could be sent or not.
     *
     * @return true if we need to check whether a presence could be sent or not.
     */
    private boolean hasToCheckRoleToBroadcastPresence() {
        // For performance reasons the check is done based on the size of the collection.
        return rolesToBroadcastPresence.size() < 3;
    }

    public boolean canBroadcastPresence(String roleToBroadcast) {
        return "none".equals(roleToBroadcast) || rolesToBroadcastPresence.contains(roleToBroadcast);
    }

    public void lock(MUCRole senderRole) throws ForbiddenException {
        if (MUCRole.Affiliation.owner != senderRole.getAffiliation()) {
            throw new ForbiddenException();
        }
        if (isLocked()) {
            // Do nothing if the room was already locked
            return;
        }
        setLocked(true);
    }

    public void unlock(MUCRole senderRole) throws ForbiddenException {
        if (MUCRole.Affiliation.owner != senderRole.getAffiliation()) {
            throw new ForbiddenException();
        }
        if (!isLocked()) {
            // Do nothing if the room was already unlocked
            return;
        }
        setLocked(false);
    }

    private void setLocked(boolean locked) {
        if (locked) {
            this.lockedTime = System.currentTimeMillis();
        }
        else {
            this.lockedTime = 0;
        }
        MUCPersistenceManager.updateRoomLock(this);
    }

    /**
     * Sets the date when the room was locked. Initially when the room is created it is locked so
     * the locked date is the creation date of the room. Afterwards, the room may be manually
     * locked and unlocked so the locked date may be in these cases different than the creation
     * date. A Date with time 0 means that the the room is unlocked.
     *
     * @param lockedTime the date when the room was locked.
     */
    void setLockedDate(Date lockedTime) {
        this.lockedTime = lockedTime.getTime();
    }

    /**
     * Returns the date when the room was locked. Initially when the room is created it is locked so
     * the locked date is the creation date of the room. Afterwards, the room may be manually
     * locked and unlocked so the locked date may be in these cases different than the creation
     * date. When the room is unlocked a Date with time 0 is returned.
     *
     * @return the date when the room was locked.
     */
    Date getLockedDate() {
        return new Date(lockedTime);
    }

    public List<Presence> addAdmins(List<JID> newAdmins, MUCRole senderRole)
            throws ForbiddenException, ConflictException {
        List<Presence> answer = new ArrayList<Presence>(newAdmins.size());
        for (JID newAdmin : newAdmins) {
        	final JID bareJID = newAdmin.asBareJID();
            if (!admins.contains(bareJID)) {
                answer.addAll(addAdmin(bareJID, senderRole));
            }
        }
        return answer;
    }

    public List<Presence> addOwners(List<JID> newOwners, MUCRole senderRole)
            throws ForbiddenException {
        List<Presence> answer = new ArrayList<Presence>(newOwners.size());
        for (JID newOwner : newOwners) {
        	final JID bareJID = newOwner.asBareJID();
            if (!owners.contains(newOwner)) {
                answer.addAll(addOwner(bareJID, senderRole));
            }
        }
        return answer;
    }

    public void saveToDB() {
        // Make the room persistent
        MUCPersistenceManager.saveToDB(this);
        if (!savedToDB) {
            // Set that the room is now in the DB
            savedToDB = true;
            // Save the existing room owners to the DB
            for (JID owner : owners) {
                MUCPersistenceManager.saveAffiliationToDB(
                    this,
                    owner,
                    null,
                    MUCRole.Affiliation.owner,
                    MUCRole.Affiliation.none);
            }
            // Save the existing room admins to the DB
            for (JID admin : admins) {
                MUCPersistenceManager.saveAffiliationToDB(
                    this,
                    admin,
                    null,
                    MUCRole.Affiliation.admin,
                    MUCRole.Affiliation.none);
            }
            // Save the existing room members to the DB
            for (JID bareJID : members.keySet()) {
                MUCPersistenceManager.saveAffiliationToDB(this, bareJID, members.get(bareJID),
                        MUCRole.Affiliation.member, MUCRole.Affiliation.none);
            }
            // Save the existing room outcasts to the DB
            for (JID outcast : outcasts) {
                MUCPersistenceManager.saveAffiliationToDB(
                    this,
                    outcast,
                    null,
                    MUCRole.Affiliation.outcast,
                    MUCRole.Affiliation.none);
            }
        }
    }

    public void writeExternal(ObjectOutput out) throws IOException {
        ExternalizableUtil.getInstance().writeSafeUTF(out, name);
        ExternalizableUtil.getInstance().writeLong(out, startTime);
        ExternalizableUtil.getInstance().writeLong(out, lockedTime);
        ExternalizableUtil.getInstance().writeSerializableCollection(out, owners);
        ExternalizableUtil.getInstance().writeSerializableCollection(out, admins);
        ExternalizableUtil.getInstance().writeSerializableMap(out, members);
        ExternalizableUtil.getInstance().writeSerializableCollection(out, outcasts);
        ExternalizableUtil.getInstance().writeSafeUTF(out, naturalLanguageName);
        ExternalizableUtil.getInstance().writeSafeUTF(out, description);
        ExternalizableUtil.getInstance().writeBoolean(out, canOccupantsChangeSubject);
        ExternalizableUtil.getInstance().writeInt(out, maxUsers);
        ExternalizableUtil.getInstance().writeStringList(out, rolesToBroadcastPresence);
        ExternalizableUtil.getInstance().writeBoolean(out, publicRoom);
        ExternalizableUtil.getInstance().writeBoolean(out, persistent);
        ExternalizableUtil.getInstance().writeBoolean(out, moderated);
        ExternalizableUtil.getInstance().writeBoolean(out, membersOnly);
        ExternalizableUtil.getInstance().writeBoolean(out, canOccupantsInvite);
        ExternalizableUtil.getInstance().writeSafeUTF(out, password);
        ExternalizableUtil.getInstance().writeBoolean(out, canAnyoneDiscoverJID);
        ExternalizableUtil.getInstance().writeBoolean(out, logEnabled);
        ExternalizableUtil.getInstance().writeBoolean(out, loginRestrictedToNickname);
        ExternalizableUtil.getInstance().writeBoolean(out, canChangeNickname);
        ExternalizableUtil.getInstance().writeBoolean(out, registrationEnabled);
        ExternalizableUtil.getInstance().writeSafeUTF(out, subject);
        ExternalizableUtil.getInstance().writeLong(out, roomID);
        ExternalizableUtil.getInstance().writeLong(out, creationDate.getTime());
        ExternalizableUtil.getInstance().writeLong(out, modificationDate.getTime());
        ExternalizableUtil.getInstance().writeBoolean(out, emptyDate != null);
        if (emptyDate != null) {
            ExternalizableUtil.getInstance().writeLong(out, emptyDate.getTime());
        }
        ExternalizableUtil.getInstance().writeBoolean(out, savedToDB);
        ExternalizableUtil.getInstance().writeSafeUTF(out, mucService.getServiceName());
    }

    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        name = ExternalizableUtil.getInstance().readSafeUTF(in);
        startTime = ExternalizableUtil.getInstance().readLong(in);
        lockedTime = ExternalizableUtil.getInstance().readLong(in);
        ExternalizableUtil.getInstance().readSerializableCollection(in, owners, getClass().getClassLoader());
        ExternalizableUtil.getInstance().readSerializableCollection(in, admins, getClass().getClassLoader());
        ExternalizableUtil.getInstance().readSerializableMap(in, members, getClass().getClassLoader());
        ExternalizableUtil.getInstance().readSerializableCollection(in, outcasts, getClass().getClassLoader());
        naturalLanguageName = ExternalizableUtil.getInstance().readSafeUTF(in);
        description = ExternalizableUtil.getInstance().readSafeUTF(in);
        canOccupantsChangeSubject = ExternalizableUtil.getInstance().readBoolean(in);
        maxUsers = ExternalizableUtil.getInstance().readInt(in);
        rolesToBroadcastPresence.addAll(ExternalizableUtil.getInstance().readStringList(in));
        publicRoom = ExternalizableUtil.getInstance().readBoolean(in);
        persistent = ExternalizableUtil.getInstance().readBoolean(in);
        moderated = ExternalizableUtil.getInstance().readBoolean(in);
        membersOnly = ExternalizableUtil.getInstance().readBoolean(in);
        canOccupantsInvite = ExternalizableUtil.getInstance().readBoolean(in);
        password = ExternalizableUtil.getInstance().readSafeUTF(in);
        canAnyoneDiscoverJID = ExternalizableUtil.getInstance().readBoolean(in);
        logEnabled = ExternalizableUtil.getInstance().readBoolean(in);
        loginRestrictedToNickname = ExternalizableUtil.getInstance().readBoolean(in);
        canChangeNickname = ExternalizableUtil.getInstance().readBoolean(in);
        registrationEnabled = ExternalizableUtil.getInstance().readBoolean(in);
        subject = ExternalizableUtil.getInstance().readSafeUTF(in);
        roomID = ExternalizableUtil.getInstance().readLong(in);
        creationDate = new Date(ExternalizableUtil.getInstance().readLong(in));
        modificationDate = new Date(ExternalizableUtil.getInstance().readLong(in));
        if (ExternalizableUtil.getInstance().readBoolean(in)) {
            emptyDate = new Date(ExternalizableUtil.getInstance().readLong(in));
        }
        savedToDB = ExternalizableUtil.getInstance().readBoolean(in);
        String subdomain = ExternalizableUtil.getInstance().readSafeUTF(in);
        mucService = XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatService(subdomain);
        if (mucService == null) throw new IllegalArgumentException("MUC service not found for subdomain: " + subdomain);
        roomHistory = new MUCRoomHistory(this, new HistoryStrategy(mucService.getHistoryStrategy()));

        PacketRouter packetRouter = XMPPServer.getInstance().getPacketRouter();
        this.iqOwnerHandler = new IQOwnerHandler(this, packetRouter);
        this.iqAdminHandler = new IQAdminHandler(this, packetRouter);

        router = packetRouter;
    }

    public void updateConfiguration(LocalMUCRoom otherRoom) {
        startTime = otherRoom.startTime;
        lockedTime = otherRoom.lockedTime;
        owners = otherRoom.owners;
        admins = otherRoom.admins;
        members = otherRoom.members;
        outcasts = otherRoom.outcasts;
        naturalLanguageName = otherRoom.naturalLanguageName;
        description = otherRoom.description;
        canOccupantsChangeSubject = otherRoom.canOccupantsChangeSubject;
        maxUsers = otherRoom.maxUsers;
        rolesToBroadcastPresence = otherRoom.rolesToBroadcastPresence;
        publicRoom = otherRoom.publicRoom;
        persistent = otherRoom.persistent;
        moderated = otherRoom.moderated;
        membersOnly = otherRoom.membersOnly;
        canOccupantsInvite = otherRoom.canOccupantsInvite;
        password = otherRoom.password;
        canAnyoneDiscoverJID = otherRoom.canAnyoneDiscoverJID;
        logEnabled = otherRoom.logEnabled;
        loginRestrictedToNickname = otherRoom.loginRestrictedToNickname;
        canChangeNickname = otherRoom.canChangeNickname;
        registrationEnabled = otherRoom.registrationEnabled;
        subject = otherRoom.subject;
        roomID = otherRoom.roomID;
        creationDate = otherRoom.creationDate;
        modificationDate = otherRoom.modificationDate;
        emptyDate = otherRoom.emptyDate;
        savedToDB = otherRoom.savedToDB;
        mucService = otherRoom.mucService;
    }

    /*
     * (non-Javadoc)
     * @see org.jivesoftware.util.resultsetmanager.Result#getUID()
     */
	public String getUID()
	{
		// name is unique for each one particular MUC service.
		return name;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result
				+ ((creationDate == null) ? 0 : creationDate.hashCode());
		result = prime * result
				+ ((description == null) ? 0 : description.hashCode());
		result = prime * result + ((name == null) ? 0 : name.hashCode());
		result = prime * result
				+ ((password == null) ? 0 : password.hashCode());
		result = prime * result + (int) (roomID ^ (roomID >>> 32));
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		LocalMUCRoom other = (LocalMUCRoom) obj;
		if (creationDate == null) {
			if (other.creationDate != null)
				return false;
		} else if (!creationDate.equals(other.creationDate))
			return false;
		if (description == null) {
			if (other.description != null)
				return false;
		} else if (!description.equals(other.description))
			return false;
		if (name == null) {
			if (other.name != null)
				return false;
		} else if (!name.equals(other.name))
			return false;
		if (password == null) {
			if (other.password != null)
				return false;
		} else if (!password.equals(other.password))
			return false;
		if (roomID != other.roomID)
			return false;
		return true;
	}
}
