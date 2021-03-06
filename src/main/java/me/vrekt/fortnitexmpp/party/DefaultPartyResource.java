package me.vrekt.fortnitexmpp.party;

import com.google.common.flogger.FluentLogger;
import me.vrekt.fortnitexmpp.FortniteXMPP;
import me.vrekt.fortnitexmpp.party.implementation.DefaultParty;
import me.vrekt.fortnitexmpp.party.implementation.Party;
import me.vrekt.fortnitexmpp.party.implementation.configuration.PartyConfiguration;
import me.vrekt.fortnitexmpp.party.implementation.configuration.PrivacySetting;
import me.vrekt.fortnitexmpp.party.implementation.data.ImmutablePartyData;
import me.vrekt.fortnitexmpp.party.implementation.listener.PartyListener;
import me.vrekt.fortnitexmpp.party.implementation.member.PartyMember;
import me.vrekt.fortnitexmpp.party.implementation.member.connection.ConnectionType;
import me.vrekt.fortnitexmpp.party.implementation.member.data.ImmutablePartyMemberData;
import me.vrekt.fortnitexmpp.party.implementation.presence.PartyPresence;
import me.vrekt.fortnitexmpp.party.implementation.request.PartyRequest;
import me.vrekt.fortnitexmpp.party.implementation.request.general.InvitationResponse;
import me.vrekt.fortnitexmpp.party.type.PartyType;
import me.vrekt.fortnitexmpp.utility.FindPlatformUtility;
import me.vrekt.fortnitexmpp.utility.JsonUtility;
import me.vrekt.fortnitexmpp.utility.Logging;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.StanzaListener;
import org.jivesoftware.smack.filter.StanzaTypeFilter;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.jivesoftware.smackx.muc.MultiUserChatManager;
import org.jxmpp.jid.Jid;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.jid.parts.Resourcepart;

import javax.json.Json;
import javax.json.JsonObject;
import java.io.StringReader;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DefaultPartyResource implements PartyResource {

    private static final FluentLogger LOGGER = FluentLogger.forEnclosingClass();

    private final Map<String, Party> parties = new ConcurrentHashMap<>();
    private final List<PartyListener> listeners = new CopyOnWriteArrayList<>();
    private final MessageListener messageListener = new MessageListener();
    private final String displayName, accountId;

    private XMPPTCPConnection connection;

    private MultiUserChatManager manager;

    private boolean enableLogging;

    /**
     * Initialize this resource
     *
     * @param fortniteXMPP the {@link FortniteXMPP} instance
     */
    public DefaultPartyResource(final FortniteXMPP fortniteXMPP, final boolean enableLogging) {
        this.connection = fortniteXMPP.connection();
        this.displayName = fortniteXMPP.displayName();
        this.accountId = fortniteXMPP.accountId();
        this.enableLogging = enableLogging;
        connection.addAsyncStanzaListener(messageListener, StanzaTypeFilter.MESSAGE);
        this.manager = MultiUserChatManager.getInstanceFor(connection);
    }

    @Override
    public void addPartyListener(final PartyListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removePartyListener(final PartyListener listener) {
        listeners.add(listener);
    }

    @Override
    public void sendRequestTo(final PartyRequest request, final Jid recipient) {
        sendTo(request, recipient);
    }

    @Override
    public void sendRequestTo(final PartyRequest request, final Collection<Jid> recipients) {
        recipients.forEach(jid -> sendTo(request, jid));
    }

    @Override
    public void sendRequestTo(final PartyRequest request, final Iterable<PartyMember> members) {
        members.forEach(member -> sendTo(request, member.user()));
    }

    @Override
    public boolean trySendRequestTo(final PartyRequest request, final Jid recipient) {
        return sendTo(request, recipient);
    }

    @Override
    public boolean trySendRequestTo(final PartyRequest request, final Collection<Jid> recipients) {
        final var failed = new AtomicBoolean(false);
        recipients.forEach(recipient -> {
            if (sendTo(request, recipient)) failed.set(true);
        });
        return failed.get();
    }

    @Override
    public boolean trySendRequestTo(final PartyRequest request, final Iterable<PartyMember> members) {
        final var failed = new AtomicBoolean(false);
        members.forEach(recipient -> {
            if (sendTo(request, recipient.user())) failed.set(true);
        });
        return failed.get();
    }

    /**
     * Send a request to the specified recipient
     *
     * @param request   the request
     * @param recipient the recipient
     */
    private boolean sendTo(final PartyRequest request, final Jid recipient) {
        if (request == null) {
            LOGGER.atWarning().log("Request was null! Did you forget to build it?");
            return true;
        }
        Logging.logInfoIfApplicable(LOGGER.atInfo(), enableLogging, "Sending request to: " + recipient.asUnescapedString() + "\nWith payload: " + request.payload());

        try {
            final var message = new Message(recipient, Message.Type.normal);
            message.setBody(request.payload());
            connection.sendStanza(message);
        } catch (final SmackException.NotConnectedException | InterruptedException exception) {
            LOGGER.atWarning().withCause(exception).log("Failed to send party request.");
            return true;
        }
        return false;
    }

    @Override
    public Party getPartyById(final String partyId) {
        return parties.get(partyId);
    }

    @Override
    public void removePartyById(final String partyId) {
        parties.remove(partyId);
    }

    @Override
    public void setPartyPresence(final PartyPresence presence) {
        final var packet = new Presence(Presence.Type.available, presence.status(), 0, Presence.Mode.available);
        try {
            connection.sendStanza(packet);
        } catch (final SmackException.NotConnectedException | InterruptedException exception) {
            LOGGER.atWarning().withCause(exception).log("Failed to send party request.");
        }
    }

    @Override
    public boolean sendMessageToParty(final Party party, final String message) {
        return sendPartyMessage(party.partyId(), message);
    }

    @Override
    public boolean sendMessageToParty(String partyId, String message) {
        return sendPartyMessage(partyId, message);
    }

    /**
     * Sends a message to the party MUC.
     *
     * @param partyId the ID of the party
     * @param message the message
     * @return {@code true} if sending was successful
     */
    private boolean sendPartyMessage(final String partyId, final String message) {
        try {
            final var room = JidCreate.entityBareFromOrThrowUnchecked(
                    "Party-" + partyId + "@muc.prod.ol.epicgames.com"
            );
            final var nick = Resourcepart.fromOrThrowUnchecked(
                    displayName + ":" + accountId + ":" + connection.getUser().getResourceOrEmpty().toString()
            );
            final var chat = manager.getMultiUserChat(room);
            try {
                chat.join(nick);
                chat.sendMessage(message);
            } catch (final Exception exception) {
                LOGGER.atWarning().log("Failed to create or send a message to party: " + partyId);
            }

        } catch (final Exception exception) {
            LOGGER.atWarning().withCause(exception).log("Failed to send message to party: " + partyId);
        }
        return false;
    }

    @Override
    public MultiUserChat getChatForParty(final Party party) {
        final var room = JidCreate.entityBareFromOrThrowUnchecked("Party-" + party.partyId() + "@muc.prod.ol.epicgames.com");
        return manager.getMultiUserChat(room);
    }

    @Override
    public MultiUserChat getChatForParty(final String partyId) {
        final var room = JidCreate.entityBareFromOrThrowUnchecked("Party-" + partyId + "@muc.prod.ol.epicgames.com");
        return manager.getMultiUserChat(room);
    }

    @Override
    public void close() {
        connection.removeAsyncStanzaListener(messageListener);
        listeners.clear();
        parties.clear();
        connection = null;
    }

    @Override
    public void disposeConnection() {
        connection.removeAsyncStanzaListener(messageListener);

    }

    @Override
    public void reinitialize(final FortniteXMPP fortniteXMPP) {
        this.connection = fortniteXMPP.connection();
        connection.addAsyncStanzaListener(messageListener, StanzaTypeFilter.MESSAGE);
        this.manager = MultiUserChatManager.getInstanceFor(connection);
    }

    /**
     * Listens for the party messages
     */
    private final class MessageListener implements StanzaListener {
        @Override
        public void processStanza(final Stanza packet) {
            final var message = (Message) packet;
            if (message.getType() != Message.Type.normal) return;

            // return here since we received something from ourself.
            if (message.getFrom().getLocalpartOrNull().equals(connection.getUser().getLocalpart())) return;

            try {
                final var reader = Json.createReader(new StringReader(message.getBody()));
                final var data = reader.readObject();
                reader.close();

                // acts to log all message even if they are not a party message
                Logging.logInfoIfApplicable(LOGGER.atInfo(), enableLogging, "Received XMPP message from: " + message.getFrom().asUnescapedString() + "\nWith payload: " + data.toString());

                final var payload = data.getJsonObject("payload");

                final var type = PartyType.typeOf(data.getString("type"));
                if (type == null) return; // not relevant

                // TODO: Move this down later? This will print messages from other stuff like friends, etc.
                listeners.forEach(listener -> listener.onMessageReceived(message));

                // update the build id
                JsonUtility.getString("buildId", payload).ifPresent(buildId -> DefaultParty.buildId = Integer.valueOf(buildId));
                JsonUtility.getString("buildid", payload).ifPresent(buildId -> DefaultParty.buildId = Integer.valueOf(buildId));

                final var partyId = JsonUtility.getString("partyId", payload);
                final var accessKey = JsonUtility.getString("accessKey", payload);

                if (partyId.isEmpty()) return; // invalid packet?
                final var from = message.getFrom();

                var party = parties.get(partyId.get());
                if (party != null && accessKey.isPresent() && !party.accessKey().equals(accessKey.get())) {
                    Logging.logInfoIfApplicable(LOGGER.atInfo(), enableLogging, "Access key changed for party: " + party.partyId() + ". Attempting to retrieve new key if possible.");
                    party = Party.fromPayload(payload);

                    // update party leader
                    final var accountId = from.getLocalpartOrNull().asUnescapedString();
                    party.updatePartyLeaderId(accountId, from);
                    parties.put(partyId.get(), party);
                }

                if (party == null) {
                    party = Party.fromPayload(payload);
                    Logging.logInfoIfApplicable(LOGGER.atInfo(), enableLogging, "Party " + party.partyId() + " created!");

                    // update party leader
                    final var accountId = from.getLocalpartOrNull().asUnescapedString();
                    party.updatePartyLeaderId(accountId, from);
                    parties.put(partyId.get(), party);
                }

                // update the party and then invoke listeners.
                updatePartyBasedOnType(party, type, payload, from);
                invokeListeners(party, type, payload, from);
            } catch (final Exception exception) {
                LOGGER.atWarning().withCause(exception).log("Failed to parse party message. from: " + packet.getFrom().asUnescapedString() + "\nPayload: " + message.getBody());
            }
        }
    }

    /**
     * Updates the party based on what type of packet was received.
     *
     * @param party   the party
     * @param type    the type of packet
     * @param payload the payload sent
     * @param from    who it was sent from
     */
    private void updatePartyBasedOnType(final Party party, final PartyType type, final JsonObject payload, final Jid from) {

        // join request has data about party members already in the party.
        if (type == PartyType.PARTY_JOIN_REQUEST_APPROVED) {
            JsonUtility.getArray("members", payload).ifPresentOrElse(array -> array.forEach(value -> {
                final var object = value.asJsonObject();
                party.addMember(PartyMember.newMember(object));
            }), () -> logMalformedType(type, payload, from));
            // a member joined, verify the request is valid.
        } else if (type == PartyType.PARTY_MEMBER_JOINED) {
            JsonUtility.getObject("member", payload)
                    .ifPresentOrElse(object -> party.addMember(PartyMember.newMember(object)), () -> logMalformedType(type, payload, from));
            // a member exited
        } else if (type == PartyType.PARTY_MEMBER_EXITED) {
            final var memberId = JsonUtility.getString("memberId", payload);
            final var kicked = JsonUtility.getBoolean("wasKicked", payload);
            if (memberId.isEmpty() || kicked.isEmpty()) {
                logMalformedType(type, payload, from);
                return;
            }

            party.removeMemberById(memberId.get());
            // party data was received
        } else if (type == PartyType.PARTY_DATA) {
            final var innerPayload = JsonUtility.getObject("payload", payload);
            final var attributes = JsonUtility.getObject("Attrs", innerPayload.orElse(null));
            final var privacySettings_j = JsonUtility.getObject("PrivacySettings_j", attributes.orElse(null));
            final var privacySettings = JsonUtility.getObject("PrivacySettings", privacySettings_j.orElse(null));

            // TODO: Implement new squad assignment data, just return for now since there is no useful data here.
            if (innerPayload.isEmpty() || attributes.isEmpty() || privacySettings_j.isEmpty() || privacySettings.isEmpty()) return;

            // reverse this, because the original value is called "onlyLeaderFriendsCanJoin"
            final var allowFriendsOfFriends = !JsonUtility.getBoolean("bOnlyLeaderFriendsCanJoin", privacySettings.get()).orElse(false);
            final var partyType = JsonUtility.getString("partType", privacySettings.get()).orElse("Public");

            // public party
            if (partyType.equalsIgnoreCase("Public")) {
                party.updateConfiguration(new PartyConfiguration(PrivacySetting.PUBLIC, party.configuration().maxMembers(), party.configuration().presencePermissions()));
                // private party
            } else if (partyType.equalsIgnoreCase("Private")) {
                party.updateConfiguration(new PartyConfiguration(allowFriendsOfFriends ? PrivacySetting.PRIVATE_ALLOW_FRIENDS_OF_FRIENDS : PrivacySetting.PRIVATE,
                        party.configuration().maxMembers(), party.configuration().presencePermissions()));
                // friends only
            } else if (partyType.equalsIgnoreCase("FriendsOnly")) {
                party.updateConfiguration(new PartyConfiguration(allowFriendsOfFriends ? PrivacySetting.FRIENDS_ALLOW_FRIENDS_OF_FRIENDS : PrivacySetting.FRIENDS,
                        party.configuration().maxMembers(), party.configuration().presencePermissions()));
            }
            // the configuration
        } else if (type == PartyType.PARTY_CONFIGURATION) {
            final var presencePermissions = JsonUtility.getLong("presencePermissions", payload);
            final var invitePermissions = JsonUtility.getInt("invitePermissions", payload);
            final var partyFlags = JsonUtility.getInt("partyFlags", payload);
            final var notAcceptingMembersReason = JsonUtility.getInt("notAcceptingMembersReason", payload);
            final var maxMembers = JsonUtility.getInt("maxMembers", payload);
            if (presencePermissions.isEmpty() || invitePermissions.isEmpty() || partyFlags.isEmpty() || notAcceptingMembersReason.isEmpty() || maxMembers.isEmpty()) {
                logMalformedType(type, payload, from);
                return;
            }

            party.updateConfiguration(new PartyConfiguration(invitePermissions.get(), partyFlags.get(), notAcceptingMembersReason.get(), maxMembers.get(), presencePermissions.get()));
        } else if (type == PartyType.PARTY_MEMBER_PROMOTED) {
            // member was promoted, change the party leader
            final var newLeaderId = JsonUtility.getString("promotedMemberUserId", payload);
            if (newLeaderId.isEmpty()) {
                logMalformedType(type, payload, from);
                return;
            }

            final var newJid = JidCreate.bareFromOrThrowUnchecked(newLeaderId + "@" + FortniteXMPP.SERVICE_DOMAIN);
            party.updatePartyLeaderId(newLeaderId.get(), newJid);
        }
    }

    /**
     * Invokes the listeners for what type was received and parses the payload if needed.
     * This method logs a warning and returns if an invalid payload was received.
     *
     * @param party the party
     * @param type  the type of packet
     * @param from  who it was sent from
     */
    private void invokeListeners(final Party party, final PartyType type, final JsonObject payload, final Jid from) {
        if (type == PartyType.PARTY_INVITATION) {
            // an invitation
            listeners.forEach(listener -> listener.onInvitation(party, from));
        } else if (type == PartyType.PARTY_INVITATION_RESPONSE) {
            // an invitation response, used for notifying whoever sent the invite what they did
            // (accepted, rejected, etc)
            final var response = JsonUtility.getInt("response", payload);
            if (response.isEmpty()) {
                logMalformedType(type, payload, from);
                return;
            }

            listeners.forEach(listener -> listener.onInvitationResponse(party, response.get() == 1 ? InvitationResponse.ACCEPTED : InvitationResponse.REJECTED, from));
        } else if (type == PartyType.PARTY_QUERY_JOINABILITY) {
            // checks if the party is joinable, checks the cross play preference aswell
            final var joinData = JsonUtility.getObject("joinData", payload);
            final var attributes = JsonUtility.getObject("Attrs", joinData.orElse(null));
            final var crossplayPreference = JsonUtility.getInt("CrossplayPreference_i", attributes.orElse(null));
            if (joinData.isEmpty() || attributes.isEmpty() || crossplayPreference.isEmpty()) {
                logMalformedType(type, payload, from);
                return;
            }
            listeners.forEach(listener -> listener.onQueryJoinability(party, crossplayPreference.get(), from));
        } else if (type == PartyType.PARTY_QUERY_JOINABILITY_RESPONSE) {
            // the response to a query, rejection types are mostly unknown
            // but result param seems to be an account ID most of the time
            final var isJoinable = JsonUtility.getBoolean("isJoinable", payload);
            final var rejectionType = JsonUtility.getInt("rejectionType", payload);
            final var resultParam = JsonUtility.getString("resultParam", payload);
            if (isJoinable.isEmpty() || rejectionType.isEmpty() || resultParam.isEmpty()) {
                logMalformedType(type, payload, from);
                return;
            }
            listeners.forEach(listener -> listener.onQueryJoinabilityResponse(party, isJoinable.get(), rejectionType.get(), resultParam.get(), from));
        } else if (type == PartyType.PARTY_JOIN_REQUEST) {
            // a request to join the party
            final var accountId = from.getLocalpartOrNull();
            final var resource = from.getResourceOrNull();

            final var displayName = JsonUtility.getString("displayName", payload);
            final var connectionType = JsonUtility.getString("connectionType", payload);
            if (accountId == null || resource == null || displayName.isEmpty() || connectionType.isEmpty()) {
                logMalformedType(type, payload, from);
                return;
            }

            // 8.3
            listeners.forEach(listener -> listener.onJoinRequest(party, PartyMember.newMember(
                    accountId.asUnescapedString(),
                    resource.toString(),
                    displayName.get(),
                    FindPlatformUtility.getPlatformForResource(resource.toString()),
                    ConnectionType.getType(connectionType.get())), from));

            // request rejected
        } else if (type == PartyType.PARTY_JOIN_REQUEST_REJECTED) {
            listeners.forEach(listener -> listener.onJoinRequestRejected(party, from));
        } else if (type == PartyType.PARTY_JOIN_REQUEST_APPROVED) {
            // join request was approved, here is where the client will notify us
            // of every member in the party, with this we can get that information and
            // then add them to the party
            final var members = JsonUtility.getArray("members", payload);
            if (members.isEmpty()) {
                logMalformedType(type, payload, from);
                return;
            }

            final var array = members.get();
            final var set = new HashSet<PartyMember>();
            array.forEach(value -> set.add(PartyMember.newMember(value.asJsonObject())));
            set.forEach(party::addMember);

            listeners.forEach(listener -> listener.onJoinRequestApproved(party, set, from));
            // a join was acknowledged
        } else if (type == PartyType.PARTY_JOIN_ACKNOWLEDGED) {
            listeners.forEach(listener -> listener.onJoinAcknowledged(party, from));
            // a response to the join acknowledged
        } else if (type == PartyType.PARTY_JOIN_ACKNOWLEDGED_RESPONSE) {
            listeners.forEach(listener -> listener.onJoinAcknowledgedResponse(party, from));
        } else if (type == PartyType.PARTY_MEMBER_DATA) {
            listeners.forEach(listener -> listener.onPartyMemberDataReceived(party, ImmutablePartyMemberData.adaptFrom(payload), from));
            // a member joined.
        } else if (type == PartyType.PARTY_MEMBER_JOINED) {
            listeners.forEach(listener -> listener.onPartyMemberJoined(party, PartyMember.newMember(payload), from));
            // a member exited.
        } else if (type == PartyType.PARTY_MEMBER_EXITED) {
            final var accountId = JsonUtility.getString("memberId", payload);
            final var wasKicked = JsonUtility.getBoolean("wasKicked", payload);
            if (accountId.isEmpty() || wasKicked.isEmpty()) {
                logMalformedType(type, payload, from);
                return;
            }
            listeners.forEach(listener -> listener.onPartyMemberExited(party, accountId.get(), wasKicked.get(), from));
            // a member was promoted
        } else if (type == PartyType.PARTY_MEMBER_PROMOTED) {
            final var accountId = JsonUtility.getString("promotedMemberUserId", payload);
            final var wasFromLeaderLeaving = JsonUtility.getBoolean("fromLeaderLeaving", payload);
            if (accountId.isEmpty() || wasFromLeaderLeaving.isEmpty()) {
                logMalformedType(type, payload, from);
                return;
            }
            listeners.forEach(listener -> listener.onPartyMemberPromoted(party, accountId.get(), wasFromLeaderLeaving.get(), from));
            // party config, privacy related.
        } else if (type == PartyType.PARTY_CONFIGURATION) {
            final var presencePermissions = JsonUtility.getLong("presencePermissions", payload);
            final var invitePermissions = JsonUtility.getInt("invitePermissions", payload);
            final var partyFlags = JsonUtility.getInt("partyFlags", payload);
            final var notAcceptingMembersReason = JsonUtility.getInt("notAcceptingMembersReason", payload);
            final var maxMembers = JsonUtility.getInt("maxMembers", payload);
            if (presencePermissions.isEmpty() || invitePermissions.isEmpty() || partyFlags.isEmpty() || notAcceptingMembersReason.isEmpty() || maxMembers.isEmpty()) {
                logMalformedType(type, payload, from);
                return;
            }
            listeners.forEach(listener -> listener.onPartyConfigurationUpdated(party,
                    new PartyConfiguration(invitePermissions.get(), partyFlags.get(), notAcceptingMembersReason.get(), maxMembers.get(), presencePermissions.get()), from));
            // party data
        } else if (type == PartyType.PARTY_DATA) {
            final var data = ImmutablePartyData.adaptFrom(payload);
            if (data == null) {
                logMalformedType(type, payload, from);
                return;
            }
            listeners.forEach(listener -> listener.onPartyData(party, data, from));
        }
    }

    /**
     * Log the malformed type received
     *
     * @param type    the type
     * @param payload the payload sent
     * @param from    who it was sent from
     */
    private void logMalformedType(final PartyType type, final JsonObject payload, final Jid from) {
        LOGGER.atWarning().log("Invalid party message received from: " + from.asUnescapedString() + "\nType: " + type.getName() + "\nPayload as string: " + payload.toString());
    }

}
