/*****************************************************************
JADE - Java Agent DEvelopment Framework is a framework to develop 
multi-agent systems in compliance with the FIPA specifications.
Copyright (C) 2000 CSELT S.p.A. 

GNU Lesser General Public License

This library is free software; you can redistribute it and/or
modify it under the terms of the GNU Lesser General Public
License as published by the Free Software Foundation, 
version 2.1 of the License. 

This library is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
Lesser General Public License for more details.

You should have received a copy of the GNU Lesser General Public
License along with this library; if not, write to the
Free Software Foundation, Inc., 59 Temple Place - Suite 330,
Boston, MA  02111-1307, USA.
 *****************************************************************/

package chat.client.agent;

import jade.content.ContentManager;
import jade.content.abs.AbsAggregate;
import jade.content.abs.AbsConcept;
import jade.content.abs.AbsPredicate;
import jade.content.lang.Codec;
import jade.content.lang.sl.SLCodec;
import jade.content.onto.BasicOntology;
import jade.content.onto.Ontology;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.util.Logger;
import jade.util.leap.Iterator;
import jade.util.leap.Set;
import jade.util.leap.SortedSetImpl;
import chat.client.ChatGui;
import jade.domain.FIPANames;
import java.util.Random;

/*#MIDP_INCLUDE_BEGIN
import chat.client.MIDPChatGui;
#MIDP_INCLUDE_END*/
//#MIDP_EXCLUDE_BEGIN
import chat.client.AWTChatGui;
//#MIDP_EXCLUDE_END
import chat.ontology.ChatOntology;

/**
 * This agent implements the logic of the chat client running on the user
 * terminal. User interactions are handled by the ChatGui in a
 * terminal-dependent way. The ChatClientAgent performs 3 types of behaviours:
 *  - ParticipantsManager. A CyclicBehaviour that keeps the list of participants up
 * to date on the basis of the information received from the ChatManagerAgent.
 * This behaviour is also in charge of subscribing as a participant to the
 * ChatManagerAgent.
 *  - ChatListener. A CyclicBehaviour that handles messages from other chat participants.
 *  - ChatSpeaker. A OneShotBehaviour that sends a message conveying a sentence written by
 *  the user to other chat participants.
 * 
 * @author Giovanni Caire - TILAB
 */
public class ChatClientAgent extends Agent {
	private static final long serialVersionUID = 1594371294421614291L;

	private Logger logger = Logger.getMyLogger(this.getClass().getName());

	private static final String CHAT_ID = "__chat__";
	//private String CHAT_MANAGER_NAME = "manager";

	private ChatGui myGui;
	private Set participants = new SortedSetImpl();
	private Codec codec = new SLCodec();
	private Ontology onto = ChatOntology.getInstance();
	private ACLMessage spokenMsg;

	private AID[] chartManagerAgents;
	private AID myManager;
	 
	protected void setup() {
		// Register language and ontology
		ContentManager cm = getContentManager();
		cm.registerLanguage(codec);
		cm.registerOntology(onto);
		cm.setValidationMode(false);
		
		// Add initial behaviours
		addBehaviour(new ManagerSearchBehaviour(this));
		addBehaviour(new ParticipantsManager(this));
		addBehaviour(new ChatListener(this));

		// Activate the GUI
		myGui = new AWTChatGui(this);
	}

	protected void takeDown() {
		if (myGui != null) {
			myGui.dispose();
		}
	}

	private void notifyParticipantsChanged() {
		myGui.notifyParticipantsChanged(getParticipantNames());
	}

	private void notifySpoken(String speaker, String sentence) {
		myGui.notifySpoken(speaker, sentence);
	}
	
	/**
	 * Inner class ParticipantsManager. This behaviour registers as a chat
	 * participant and keeps the list of participants up to date by managing the
	 * information received from the ChatManager agent.
	 */
	class ParticipantsManager extends CyclicBehaviour {
		private static final long serialVersionUID = -4845730529175649756L;
		private MessageTemplate template;
		
		private ACLMessage subscription; 
		private AID manager;
		
		ParticipantsManager(Agent a) {
			super(a);
		}

		public void onStart() {
			
			//AID managerAID = new AID(CHAT_MANAGER_NAME, AID.ISLOCALNAME);
			
			// Subscribe as a chat participant to the ChatManager agent
			subscription = new ACLMessage(ACLMessage.SUBSCRIBE);
			subscription.setLanguage(codec.getName());
			subscription.setOntology(onto.getName());
			String convId = "C-" + myAgent.getLocalName();
			subscription.setConversationId(convId);
			myAgent.send(subscription);
			// Initialize the template used to receive notifications
			// from the ChatManagerAgent
			template = MessageTemplate.MatchConversationId(convId);
		}

		public void action() {
			checkManager();
			// Receives information about people joining and leaving
			// the chat from the ChatManager agent
			ACLMessage msg = myAgent.receive(template);
			if (msg != null) {
				if (msg.getPerformative() == ACLMessage.INFORM) {
					try {
						AbsPredicate p = (AbsPredicate) myAgent
							.getContentManager().extractAbsContent(msg);
						if (p.getTypeName().equals(ChatOntology.JOINED)) {
							// Get new participants, add them to the list of
							// participants and notify the gui
							AbsAggregate agg = (AbsAggregate) p
									.getAbsTerm(ChatOntology.JOINED_WHO);
							if (agg != null) {
								Iterator it = agg.iterator();
								while (it.hasNext()) {
									AbsConcept c = (AbsConcept) it.next();
									participants.add(BasicOntology
											.getInstance().toObject(c));
								}
							}
							notifyParticipantsChanged();
						}
						if (p.getTypeName().equals(ChatOntology.LEFT)) {
							// Get old participants, remove them from the list
							// of participants and notify the gui
							AbsAggregate agg = (AbsAggregate) p
									.getAbsTerm(ChatOntology.JOINED_WHO);
							if (agg != null) {
								Iterator it = agg.iterator();
								while (it.hasNext()) {
									AbsConcept c = (AbsConcept) it.next();
									participants.remove(BasicOntology
											.getInstance().toObject(c));
								}
							}
							notifyParticipantsChanged();
						}
					} catch (Exception e) {
						Logger.println(e.toString());
						e.printStackTrace();
					}
				} else {
					handleUnexpected(msg);
				}
			} else {
				block();
			}
		}
		
		void checkManager()
		{
			if (myManager != null)
			{
				if (manager == null)
				{
					manager = myManager;
					subscription.addReceiver(manager);
					myAgent.send(subscription);
				}
				else if (!myManager.equals(manager))
				{
					subscription.removeReceiver(manager);
					manager = myManager;
					subscription.addReceiver(manager);
					myAgent.send(subscription);
				}
			}
		}
	} // END of inner class ParticipantsManager

	/**
	 * Inner class ChatListener. This behaviour registers as a chat participant
	 * and keeps the list of participants up to date by managing the information
	 * received from the ChatManager agent.
	 */
	class ChatListener extends CyclicBehaviour {
		private static final long serialVersionUID = 741233963737842521L;
		private AID manager;
		private MessageTemplate template;

		ChatListener(Agent a) {
			super(a);
		}

		public void action() {
			checkManager();
			ACLMessage msg = myAgent.receive(template);
			if (msg != null) {
				if (msg.getPerformative() == ACLMessage.INFORM) {
					notifySpoken(msg.getSender().getLocalName(),
							msg.getContent());
				} else {
					handleUnexpected(msg);
				}
			} else {
				block();
			}
		}
		
		private void checkManager()
		{
			if (myManager != null && !myManager.equals(manager))
			{
				manager = myManager;
				template = MessageTemplate
						.MatchConversationId(manager.getName());
			}
		}
	} // END of inner class ChatListener

	/**
	 * Inner class ChatSpeaker. INFORMs other participants about a spoken
	 * sentence
	 */
	private class ChatSpeaker extends OneShotBehaviour {
		private static final long serialVersionUID = -1426033904935339194L;
		private String sentence;

		private ChatSpeaker(Agent a, String s) {
			super(a);
			sentence = s;
		}

		public void action() {
			spokenMsg.clearAllReceiver();
			Iterator it = participants.iterator();
			while (it.hasNext()) {
				spokenMsg.addReceiver((AID) it.next());
			}
			spokenMsg.setContent(sentence);
			notifySpoken(myAgent.getLocalName(), sentence);
			send(spokenMsg);
		}
	} // END of inner class ChatSpeaker

	// ///////////////////////////////////////
	// Methods called by the interface
	// ///////////////////////////////////////
	public void handleSpoken(String s) {
		// Add a ChatSpeaker behaviour that INFORMs all participants about
		// the spoken sentence
		addBehaviour(new ChatSpeaker(this, s));
	}
	
	public String[] getParticipantNames() {
		String[] pp = new String[participants.size()];
		Iterator it = participants.iterator();
		int i = 0;
		while (it.hasNext()) {
			AID id = (AID) it.next();
			pp[i++] = id.getLocalName();
		}
		return pp;
	}

	// ///////////////////////////////////////
	// Private utility method
	// ///////////////////////////////////////
	private void handleUnexpected(ACLMessage msg) {
		if (logger.isLoggable(Logger.WARNING)) {
			logger.log(Logger.WARNING, "Unexpected message received from "
					+ msg.getSender().getName());
			logger.log(Logger.WARNING, "Content is: " + msg.getContent());
		}
	}
	
	private class ManagerSearchBehaviour extends CyclicBehaviour
	{
		private static final long serialVersionUID = 4715766411703805482L;
		private DFAgentDescription dfTemplate;
		
		public ManagerSearchBehaviour(Agent a) {
			super(a);
			dfTemplate = new DFAgentDescription();
			ServiceDescription sd = new ServiceDescription();
			sd.setType("manager");
			dfTemplate.addServices(sd);
			
			action();
		}

		@Override
		public void action() {
			try {
				DFAgentDescription[] result = DFService.search(ChatClientAgent.this, dfTemplate);
				chartManagerAgents = new AID[result.length];
				for (int i = 0; i < result.length; ++i) {
					chartManagerAgents[i] = result[i].getName();
				}
			} catch (FIPAException fe) {
				fe.printStackTrace();
			}

			checkMyManager();

		}
		
		void checkMyManager()
		{
			for (AID manager : chartManagerAgents)
			{
				if (manager.equals(myManager))
					return;
			}
			Random rand = new Random();
			myManager = chartManagerAgents[rand.nextInt(chartManagerAgents.length)];
			spokenMsg.setProtocol(FIPANames.InteractionProtocol.FIPA_RECRUITING);
			spokenMsg = new ACLMessage(ACLMessage.INFORM);
			spokenMsg.setConversationId(myManager.getName());
		}

	}

}
