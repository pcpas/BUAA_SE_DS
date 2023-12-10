/*	File			CaseStudy_ENS_Server.java
	Purpose			Notification Service Server
	Author			Richard Anthony	(R.J.Anthony@gre.ac.uk)
	Date			February 2015
*/
package casestudy_ens_server;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.*;
import java.net.*;
import java.nio.*; // java.nio.ByteBuffer
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;
import javax.swing.JOptionPane;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.ScrollPaneConstants;

public class CaseStudy_ENS_Server extends javax.swing.JFrame implements ActionListener
{
    private static int ENS_APPLICATION_PORT = 8003;
    private static int MAX_EVENT_TYPE_NAME_LENGTH = 50;
    private static int MAX_EVENT_VALUE_LENGTH = 50;

    private String EVENT_NOTIFICATION_SERVER_NAME = "Event_NS";	// Event Notification Server

    private class EventNotificationService_Message_PDU      // Fixed, defined by Event Notification Service
    {
        byte bMessageType;
	byte[] cEventType;  // C++ equivallent [MAX_EVENT_TYPE_NAME_LENGTH];
	byte[] cEventValue;   // C++ equivallent [MAX_EVENT_VALUE_LENGTH];     // Not valid in Subscribe messages
	SockAddr_In SendersAddress;
    };    
    
    // SOCKADDR_IN not defined in Java - define it and its constituent sub-structures
    // Needed because the ENS passes the message sender's address in this format
    private class SockAddr_In
    {
        int sin_family;
        int sin_port;
        byte[] sin_addr; // C++ equivallent [4];
        byte[] sin_zero; // C++ equivallent [8];
    }
    
    private static int EventNotificationService_Message_PDU_Size = 120;
        
    private static int MAX_CONNECTIONS = 30;
    private static int MAX_EVENT_TYPES = 30;

    private class Connection
    {
        Socket ConnectSock;
        InputStream InStream;
        OutputStream OutStream;
        Boolean bInUse;
    };

    private class SubscriberListItem {
        int iConnectionIndex; // Index into connection array to identify the subscriber
    };

    private class Event_Type
    { 
        // An event type is dynamically created whenever a publisher publishes a new event type or a consumer subscribes to a new event type
        // This is totally run-time configured, the designer does not know anything about the event types that may exist
        // Components are decoupled at the application level as well as at the communication level:
        //		- a producer can publish a new event type regardless of whether there is any consumer for that event-type
        //		- a consumer can subscribe to a new event type regardless of whether there is any producer for that event-type
	String sEventType;
	String sEventValue;
        LinkedList<SubscriberListItem> SubscriberList;  // Linked list of subscribers
        InetSocketAddress PublisherAddress;             // IP address and Port number of most recent publisher of this event type
	Boolean bInUse_ValueValid;			// Has the event been published
	Boolean bInUse_SubscriptionExists;              // Is there at least one consumer waiting for this event
    };
     
    public Connection[] m_ConnectionArray;
    public Event_Type[] m_EventTypeArray;
        
    // Define member variables
    ServerSocket m_ListenSock;
    InetSocketAddress m_DirectoryService_IPEndPoint;   // for Broadcasting 'register' messages to the Directory server
    SockAddr_In m_LocalAddress_FormattedAs_SockAddr_In;
    Timer m_Registration_Timer;
    Timer m_ApplicationMessage_Timer;  // Needed because receives are non-blocking
    DirectoryServiceClient m_DirectoryServiceClient;
    Boolean m_bRegisterAlreadyDone;

    enum EventNotificationService_Message_Type {SubscribeToEvent, UnSubscribeToEvent, PublishEvent, EventUpdate};
        
    public CaseStudy_ENS_Server()
    {
        initComponents();
        m_ConnectionArray = new Connection[MAX_CONNECTIONS];
        m_EventTypeArray = new Event_Type[MAX_EVENT_TYPES];
        m_DirectoryServiceClient = new DirectoryServiceClient();
        m_bRegisterAlreadyDone = false;
        m_LocalAddress_FormattedAs_SockAddr_In = GetLocalIPAddress();
        CreateListenSocket();

        // Initialise the connections array
        for(int i = 0; i < MAX_CONNECTIONS; i++)
        {	
            m_ConnectionArray[i] = new Connection();
            m_ConnectionArray[i].bInUse = false;
        }	
        // Initialise the EventType_List array
        for(int i = 0; i < MAX_EVENT_TYPES; i++)
        {
            m_EventTypeArray[i] = new Event_Type();
            m_EventTypeArray[i].bInUse_SubscriptionExists = false;
            m_EventTypeArray[i].bInUse_ValueValid = false;
            m_EventTypeArray[i].SubscriberList = new LinkedList<SubscriberListItem>();  // Linked list of subscribers
        }	
        Activate_ENS();
        DisplayEventTypeList();	// Refresh the list of known event types
    }
    
    void CreateListenSocket()
    {   // TCP, non-blocking
        try
        {
            m_ListenSock = new ServerSocket(ENS_APPLICATION_PORT, 5/*backlog*/, InetAddress.getLocalHost()/*bindAddr*/);
            m_ListenSock.setSoTimeout(100); // Timeout = 100mS i.e. non-blocking
        }
        catch (SocketTimeoutException Ex)
        {   //Timeout - ignore
        }
        catch (Exception Ex)  // Catch exceptions from the socket operations
        {
            MessageBox(Ex.toString(), "ENS Server");
            CloseAndExit();
        }
    }

    public SockAddr_In GetLocalIPAddress()
    {   // Get local socket address in SockAddr_In format
        SockAddr_In SockAddr_In_LocalAddress = new SockAddr_In();
        SockAddr_In_LocalAddress.sin_addr = new byte[4];
        SockAddr_In_LocalAddress.sin_port = ENS_APPLICATION_PORT;
        SockAddr_In_LocalAddress.sin_family = 2; // Equivallent to AF_INET in C++
        String sHostAddress = "";
        try
        {
            sHostAddress = InetAddress.getLocalHost().toString(); // Expected format: "Hostname/192.168.100.5"
            String sStr = "";
            String sHostName = "";
            int iStartOfDottedIPSubstring = sHostAddress.indexOf('/');
            if(-1 == iStartOfDottedIPSubstring)
            {   // Align to the actual start of the dotted IP address part of the string
                iStartOfDottedIPSubstring = 0; // '/' not found
            }
            else
            {
                sHostName = sHostAddress.substring(0, iStartOfDottedIPSubstring);
                iStartOfDottedIPSubstring++; // Skip the '/' charachter
            }
            String sDottedIPAddress = sHostAddress.substring(iStartOfDottedIPSubstring);
            sStr = String.format("Local host: %s", sHostName);
            WriteEventLogEntry(sStr);
            sStr = String.format("Local address: %s:%d", sDottedIPAddress, ENS_APPLICATION_PORT);
            WriteEventLogEntry(sStr);
            long lAddressArray[] = StringToAddressComponents(sDottedIPAddress);
            for(int iIndex = 0; iIndex < 4; iIndex++)
            {
                SockAddr_In_LocalAddress.sin_addr[iIndex] = (byte)lAddressArray[iIndex];
            }
        }
        catch (Exception Ex)
        {
            WriteEventLogEntry(Ex.toString());
            CloseAndExit();
        }
        return SockAddr_In_LocalAddress;
    }

    void Activate_ENS()
    {
        SendRegisterMessage();              // Auto-register with Directory service immediately
        Start_Registration_Timer();         // Auto-register with Directory service periodically
        Start_ApplicationMessage_Timer();   // Deal with connection requests and received messages on short-periodic basis
    }

    void Start_Registration_Timer()
    {
        m_Registration_Timer = new Timer();
        m_Registration_Timer.scheduleAtFixedRate(new OnTimedEvent_Registration_Timer(), 10000, 10000);
        // 10 second interval between auto-registration activity
    }

    class OnTimedEvent_Registration_Timer extends TimerTask
    {
        public void run()
        {   // Periodic auto-registration
            SendRegisterMessage();
        }
    }
    
    void SendRegisterMessage()
    {
        m_DirectoryServiceClient.Register_ServerName_with_DS(EVENT_NOTIFICATION_SERVER_NAME, ENS_APPLICATION_PORT, true);
        if(false == m_bRegisterAlreadyDone)
        {
            WriteEventLogEntry("Registered with Directory Service");
            m_bRegisterAlreadyDone = true;
        }
    }
    
    void Start_ApplicationMessage_Timer()
    {
        m_ApplicationMessage_Timer = new Timer();
        m_ApplicationMessage_Timer.scheduleAtFixedRate(new OnTimedEvent_ApplicationMessage_Timer(), 100, 100);
        // 100 milli-second interval for instances of accept and recv activity
    }

    class OnTimedEvent_ApplicationMessage_Timer extends TimerTask
    {
        public void run()
        {
            Accept();	// Deal with any pending connection requests

            for (int iIndex = 0; iIndex < MAX_CONNECTIONS; iIndex++)	// Deal with any pending messages in the buffer
            {
                if (m_ConnectionArray[iIndex].bInUse == true)
                {
                    Receive(iIndex);	// For each valid listen-socket DoReceive()
                }
            }
        }
    }

    void Accept()
    {
        String sStr = "";
        int iIndex = GetIndexOfUnusedConnection();
        if (iIndex == -1)
        {
            WriteEventLogEntry("Accept refused - Maximum number of Connections reached");
            return;
        }
        try
        {
            m_ConnectionArray[iIndex].ConnectSock = m_ListenSock.accept();
        }
        catch (SocketTimeoutException Ex)
        {   
            return;
        }
        catch (Exception Ex)
        {
            sStr = String.format("Accept failed, %s", Ex.toString());    
            WriteEventLogEntry(sStr);
            return;
        }

        m_ConnectionArray[iIndex].bInUse = true;
        try 
        {   
            m_ConnectionArray[iIndex].ConnectSock.setSoTimeout(500); // 100ms timeout (non-blocking)
            m_ConnectionArray[iIndex].InStream = m_ConnectionArray[iIndex].ConnectSock.getInputStream();
            m_ConnectionArray[iIndex].OutStream = m_ConnectionArray[iIndex].ConnectSock.getOutputStream();
        }
        catch (Exception Ex)
        {
            WriteEventLogEntry(Ex.toString());
            return;
        }

        String sAddress = m_ConnectionArray[iIndex].ConnectSock.getRemoteSocketAddress().toString();
        sStr = String.format("Client %s Connected", sAddress);
        WriteEventLogEntry(sStr);
    }

    void Receive(int iConnection)
    {		
        String sStr = "";
        byte[] bBuffer = new byte[1024];  // Create an appropriately-sized buffer to Receive into
        int iReceiveByteCount = 0;
        try
        {
            iReceiveByteCount = m_ConnectionArray[iConnection].InStream.read(bBuffer);
        }
        catch (SocketTimeoutException Ex)
        {
            return;
        }
        catch (Exception Ex)
        {
            sStr = String.format("Receive failed, %s", Ex.toString());    
            WriteEventLogEntry(sStr);
            CloseConnection(iConnection);
            return;
        }
        if(-1 == iReceiveByteCount)
        {
            return; // no data
        }

        if(EventNotificationService_Message_PDU_Size == iReceiveByteCount)
        {
            // Deserialise the Receive buffer to get the ENS PDU message structure
            EventNotificationService_Message_PDU Message = DeSerialize_ENS_Message(bBuffer);
            String sStrEvent = ByteArray_To_String(Message.cEventType);
            String sStrValue = ByteArray_To_String(Message.cEventValue);
            int iEventTypeList_Index;
            EventNotificationService_Message_Type MessageTypeConvertedToEnum = 
                    EventNotificationService_Message_Type.values()[Message.bMessageType];
            switch(MessageTypeConvertedToEnum)
            {
                case SubscribeToEvent:
                    sStr = String.format("SubscribeToEvent received: %s", sStrEvent);
                    WriteEventLogEntry(sStr);
                    AddEventType(Message, iConnection);
                    DisplayEventTypeList();	// Refresh the list of known event types
                    break;
                case PublishEvent:
                    sStr = String.format("PublishEvent received: %s %s", sStrEvent, sStrValue);
                    WriteEventLogEntry(sStr);
                    iEventTypeList_Index = AddEventType(Message, iConnection);
                    DisplayEventTypeList();	// Refresh the list of known event types
                    PushEventToRegisteredSubscribers(iEventTypeList_Index);
                    break;
                case UnSubscribeToEvent:
                    sStr = String.format("UnSubscribeToEvent received: %s", sStrEvent);
                    WriteEventLogEntry(sStr);
                    RemoveSubscription(Message, iConnection);
                    DisplayEventTypeList();	// Refresh the list of known event types
                    break;
            }
        }
    }

    int GetIndexOfEvent(EventNotificationService_Message_PDU Message, Boolean bCreateNewIfNotExist)
    {
        String sEvent = ByteArray_To_String(Message.cEventType);
        int iIndex;
        // Check if the Event Type is already known
        for(iIndex = 0; iIndex < MAX_EVENT_TYPES; iIndex++)
        {
            if (true == m_EventTypeArray[iIndex].bInUse_SubscriptionExists || true == m_EventTypeArray[iIndex].bInUse_ValueValid)
            {
                if(0 == m_EventTypeArray[iIndex].sEventType.compareToIgnoreCase(sEvent))
                {	
                    WriteEventLogEntry("EventType already known");
                    return iIndex;
                }
            }
        }	

        if(false == bCreateNewIfNotExist)
        {   // Do not create a new entry if the EventType was not found (for UnSubscribe)
            WriteEventLogEntry("EventType not found");
            return -1; // Event entry was not found
        }

        // Find an unused m_EventTypeArray entry (for Subscribe, Publish)
        for(iIndex = 0; iIndex < MAX_EVENT_TYPES; iIndex++)
        {	
            if(false == m_EventTypeArray[iIndex].bInUse_SubscriptionExists && false == m_EventTypeArray[iIndex].bInUse_ValueValid)
            {
                WriteEventLogEntry("Vacant EventType entry found");
                // Add the new event type in the vacent list entry
                m_EventTypeArray[iIndex].sEventType = sEvent; 
                return iIndex;
            }
        }
        WriteEventLogEntry("EventType list FULL !");
        return -1;
    }

    int AddEventType(EventNotificationService_Message_PDU Message, int iConnection)
    {
        int iIndex;
        EventNotificationService_Message_Type MessageTypeConvertedToEnum = 
                EventNotificationService_Message_Type.values()[Message.bMessageType];
        switch(MessageTypeConvertedToEnum)
        {
            case SubscribeToEvent:
                iIndex = GetIndexOfEvent(Message, true);  // Returns index of event (creates a new event if the EventType does not already exist)
                m_EventTypeArray[iIndex].bInUse_SubscriptionExists = true;	
                AddSubscriber_to_SubscriberList(iIndex, iConnection);	// Add subscriber details to subscriber list for event type
                if(true == m_EventTypeArray[iIndex].bInUse_ValueValid)
                {   // The event had already been published, so push the current value out to the new subscriber immediately
                    Send_EventUpdate_Message(iIndex, iConnection);
                }
                return iIndex;
            case PublishEvent:
                iIndex = GetIndexOfEvent(Message, true);  // Returns index of event (creates a new event if the EventType does not already exist)
                m_EventTypeArray[iIndex].bInUse_ValueValid = true;                   
                if(0 == Message.cEventValue[0])
                {   // If EventValue string is blank clear the byte array field
                    Message.cEventValue = new byte[MAX_EVENT_VALUE_LENGTH];
                }
                m_EventTypeArray[iIndex].sEventValue = ByteArray_To_String(Message.cEventValue);

                byte[] bAddress = new byte[4];
                bAddress[0] = Message.SendersAddress.sin_addr[0];
                bAddress[1] = Message.SendersAddress.sin_addr[1];
                bAddress[2] = Message.SendersAddress.sin_addr[2];
                bAddress[3] = Message.SendersAddress.sin_addr[3];
                try
                {
                    InetAddress SenderAddress = InetAddress.getByAddress(bAddress);
                    m_EventTypeArray[iIndex].PublisherAddress = 
                        new InetSocketAddress(SenderAddress, Message.SendersAddress.sin_port);
                }
                catch (UnknownHostException Ex)
                {
                    WriteEventLogEntry(Ex.toString());
                }
                return iIndex;
        }
        return -1;
    }

    void AddSubscriber_to_SubscriberList(int iIndex, int iConnection)
    {
        SubscriberListItem ItemToAdd = new SubscriberListItem();
        ItemToAdd.iConnectionIndex = iConnection;

        // Check to see if the current subscriber is already in the list
        if(-1 != m_EventTypeArray[iIndex].SubscriberList.indexOf(ItemToAdd)) //-1 if not found
        {   // WAS found
            return;   // Subscriber already in the list for the particular EventType
        }
        m_EventTypeArray[iIndex].SubscriberList.addFirst(ItemToAdd);
    }

    void PushEventToRegisteredSubscribers(int iEventTypeList_Index)
    {
        // Iterate through the list, send an Event update message to each subscriber in the list
        ListIterator<SubscriberListItem> listIterator = m_EventTypeArray[iEventTypeList_Index].SubscriberList.listIterator();
        while (listIterator.hasNext())
        {
            Send_EventUpdate_Message(iEventTypeList_Index, listIterator.next().iConnectionIndex);
        }
    }

    void Send_EventUpdate_Message(int iEventTypeList_Index, int iConnectionIndex)
    {
        EventNotificationService_Message_PDU ENS_Message = new EventNotificationService_Message_PDU();
        ENS_Message.bMessageType = (byte)EventNotificationService_Message_Type.EventUpdate.ordinal();
        ENS_Message.cEventType = m_EventTypeArray[iEventTypeList_Index].sEventType.getBytes();
        ENS_Message.cEventValue = m_EventTypeArray[iEventTypeList_Index].sEventValue.getBytes();
        ENS_Message.SendersAddress = m_LocalAddress_FormattedAs_SockAddr_In;
        SendMessageToClient(ENS_Message, iConnectionIndex);
    }

    void SendMessageToClient(EventNotificationService_Message_PDU ENS_Message, int iConnectionIndex)
    {
        byte[] bBuffer = Serialize_ENS_PDU(ENS_Message);
        try
        {
            m_ConnectionArray[iConnectionIndex].OutStream.write(bBuffer);
        }
        catch (SocketTimeoutException Ex)
        {
            return;
        }
        catch (Exception ex)
        {
            CloseConnection(iConnectionIndex);
            return;
        }
        WriteEventLogEntry("Message sent to ENS client");
    }

    void RemoveSubscription(EventNotificationService_Message_PDU Message, int iConnection)
    {
        // Find the relevent entry in linked list
        int iIndex;
        iIndex = GetIndexOfEvent(Message, false);  // Returns index of event (does not create a new event if the EventType does not already exist)
        if(-1 == iIndex)
        {
            return;	// EventType entry not found in array
        }
        RemoveSubscriber_from_SubscriberList(iIndex, iConnection);	// Remove subscriber details from subscriber list for event type

        if(0 == m_EventTypeArray[iIndex].SubscriberList.size())
        {	// If subscriber list now emply, set subscribed flag  to false
            m_EventTypeArray[iIndex].bInUse_SubscriptionExists = false;
        }

        // If both flags are now false (the bInUse_SubscriptionExists flag and the bInUse_ValueValid flag) the EventType
        // is essentially removed, as its array entry will be re-used when a new EventType is created
    }

    void RemoveSubscriber_from_SubscriberList(int iEventTypeArrayIndex, int iConnection)
    {
        for (int iIndex = 0; iIndex < m_EventTypeArray[iEventTypeArrayIndex].SubscriberList.size(); iIndex++)
        {
            System.out.println(m_EventTypeArray[iEventTypeArrayIndex].SubscriberList.get(iIndex));
            if(iConnection == m_EventTypeArray[iEventTypeArrayIndex].SubscriberList.get(iIndex).iConnectionIndex)
            {
                m_EventTypeArray[iEventTypeArrayIndex].SubscriberList.remove(iIndex);
                WriteEventLogEntry("Unsubscribe was successful");
                return;
            }
        }
        WriteEventLogEntry("Unsubscribe: Subscriber not found in event subscriber list");
    }

    int GetIndexOfUnusedConnection()
    {
        for(int i = 0; i < MAX_CONNECTIONS; i++)
        {	
            if(m_ConnectionArray[i].bInUse == false)
            {
                return i;
            }
        }
        return -1;	// Signal that all connections are in use
    }

    private void CloseAndExit()
    {   // Close all sockets and exit the application
        for (int i = 0; i < MAX_CONNECTIONS; i++)	// Close active connections
        {
            if (m_ConnectionArray[i].bInUse == true)
            {
                CloseConnection(i);
            }
        }
        try
        {
            m_ListenSock.close();
        }
        catch (Exception Ex)
        {
        }
        System.exit(0);
    }

    void CloseConnection(int iConnection)
    {
        try
        {
             m_ConnectionArray[iConnection].ConnectSock.close();			// Close the socket
        }
        catch (Exception Ex)
        {
        }
        m_ConnectionArray[iConnection].bInUse = false;

        String sAddress = m_ConnectionArray[iConnection].ConnectSock.getRemoteSocketAddress().toString();
        String sStr = String.format("Client %s Disconnected", sAddress);
        WriteEventLogEntry(sStr);
    }

    public EventNotificationService_Message_PDU DeSerialize_ENS_Message(byte[] bRecdByteArray)
    {   // Converts a byte array (from Receive) to a EventNotificationService_Message_PDU structure (Fields have fixed length, but non-fixed data length)
        EventNotificationService_Message_PDU ENS_PDU = new EventNotificationService_Message_PDU();
        ENS_PDU.SendersAddress = new SockAddr_In();
        ENS_PDU.SendersAddress.sin_addr = new byte[4];            
        ENS_PDU.SendersAddress.sin_zero = new byte[8];
        ENS_PDU.bMessageType = bRecdByteArray[0];
        ENS_PDU.cEventType = new byte[MAX_EVENT_TYPE_NAME_LENGTH];
        System.arraycopy(bRecdByteArray/*src*/, 1, ENS_PDU.cEventType/*dest*/, 0, MAX_EVENT_TYPE_NAME_LENGTH/*count*/); 
        ENS_PDU.cEventValue = new byte[MAX_EVENT_VALUE_LENGTH];
        System.arraycopy(bRecdByteArray/*src*/, 51, ENS_PDU.cEventValue/*dest*/, 0, MAX_EVENT_VALUE_LENGTH/*count*/);
        ENS_PDU.SendersAddress.sin_family = bRecdByteArray[104];
        // DeSerialisation of port number
        // The message format and serialisation used in the C# version has been designed to interoperate with the pre-existing C++ version 
        // The port number is sent from the server as a 4-byte integer value of the format: high-byte-of-16-bit-value low-byte-of-16-bit-value 0 0  
        // the values in each byte are decimal values, for example the port number (decimal) 8000 is encoded, and received by clients, as:
        // 31 64 0 0 which tranlates into 31*256 + 64 = 8000. However, when converted directly as an Int16 we get 64*256 + 31 = 16415
        ENS_PDU.SendersAddress.sin_port = (int)((bRecdByteArray[106] * 256) + bRecdByteArray[107]);
        ENS_PDU.SendersAddress.sin_addr[0] = bRecdByteArray[108];
        ENS_PDU.SendersAddress.sin_addr[1] = bRecdByteArray[109];
        ENS_PDU.SendersAddress.sin_addr[2] = bRecdByteArray[110];
        ENS_PDU.SendersAddress.sin_addr[3] = bRecdByteArray[111];
        return ENS_PDU;
    }

    private byte[] Serialize_ENS_PDU(EventNotificationService_Message_PDU ENS_PDU)
    {   // Convert an EventNotificationService_Message_PDU into a byte array
        byte[] bByteArray = new byte[EventNotificationService_Message_PDU_Size];        // Create a byte array to hold the serialised form of the ENS_PDU
        bByteArray[0] = ENS_PDU.bMessageType;
        System.arraycopy(ENS_PDU.cEventType/*src*/, 0, bByteArray/*dest*/, 1, ENS_PDU.cEventType.length);
        System.arraycopy(ENS_PDU.cEventValue/*src*/, 0, bByteArray/*dest*/, 51, ENS_PDU.cEventValue.length);

        bByteArray[104] = (byte)ENS_PDU.SendersAddress.sin_family;
        // Serialisation of port number
        // The message format and serialisation used in the C# version has been designed to interoperate with the pre-existing C++ version 
        // The port number is sent from the server as a 4-byte integer value of the format: high-byte-of-16-bit-value low-byte-of-16-bit-value 0 0  
        // the values in each byte are decimal values, for example the port number (decimal) 8000 is encoded, and received by clients, as:
        // 31 64 0 0 which tranlates into 31*256 + 64 = 8000. However, when converted directly as an Int16 we get 64*256 + 31 = 16415
        bByteArray[106] = (byte)(ENS_PDU.SendersAddress.sin_port / 256); // Create the MSB byte of the port value
        bByteArray[107] = (byte)(ENS_PDU.SendersAddress.sin_port % 256); // Create the LSB byte of the port value
        bByteArray[108] = ENS_PDU.SendersAddress.sin_addr[0];
        bByteArray[109] = ENS_PDU.SendersAddress.sin_addr[1];
        bByteArray[110] = ENS_PDU.SendersAddress.sin_addr[2];
        bByteArray[111] = ENS_PDU.SendersAddress.sin_addr[3];
        return bByteArray;
    }

    private void WriteEventLogEntry(String sStr)
    {
        sStr += "\n";
        jTextArea_EventLog.append(sStr);       
    }
    
    void DisplayEventTypeList()
    {
        jTextArea_EventList.setText("");// Clear the Listbox
        for (int i = 0; i < MAX_EVENT_TYPES; i++)
        {
            if (true == m_EventTypeArray[i].bInUse_SubscriptionExists || true == m_EventTypeArray[i].bInUse_ValueValid)
            {
                String sStr = m_EventTypeArray[i].sEventType + "\n";
                jTextArea_EventList.append(sStr);
            }
        }
    }

    public static void main(String args[]) throws Exception
    {
        // Initialise the GUI libraries
        try 
        {
            javax.swing.UIManager.LookAndFeelInfo[] installedLookAndFeels=javax.swing.UIManager.getInstalledLookAndFeels();
            for (int idx=0; idx<installedLookAndFeels.length; idx++)
            {
                if ("Nimbus".equals(installedLookAndFeels[idx].getName())) {
                    javax.swing.UIManager.setLookAndFeel(installedLookAndFeels[idx].getClassName());
                    break;
                }
            }
        } 
        catch (Exception Ex)
        {
            System.exit(0);
        }

        // Create and display the GUI form
        java.awt.EventQueue.invokeAndWait(new Runnable()
                { public void run() { new CaseStudy_ENS_Server().setVisible(true); } } );
    }
    
    private static String ByteArray_To_String(byte[] baByteArray)
    {
        String sResult = new String(baByteArray);
        int iIndex;
        for(iIndex = 0; iIndex < MAX_EVENT_TYPE_NAME_LENGTH; iIndex++)
        {   // Determine length of String in array (find the '\0' char)
            if(0 == baByteArray[iIndex])
            break; // Value of iIndex is length of the string 
        }
        return sResult.substring(0, iIndex);
    }
    
    public long[] StringToAddressComponents(String sAddress)
    {   // sAddress can be in either dotted decimal format, or can contain a long value in string format
        long[] lAddressComponentArray = new long[4];
        String[] sStrArray = sAddress.split("\\."); // Attempt to split the string into dot-separated components
        if (4 == sStrArray.length)   // Was it a dotted address format, e.g "192.168.100.5"
        {
            for(int iIndex = 0; iIndex < 4; iIndex++)
            {
                lAddressComponentArray[iIndex] = Long.parseLong(sStrArray[iIndex]);
            }
        }
        else
        {  // Treat the string as a long decimal value in string format e.g. "123456789"
            long lTemp = Long.parseLong(sAddress);
            lAddressComponentArray[0] = (lTemp & 0xff000000) >> 24;
            lAddressComponentArray[1] = (lTemp & 0x00ff0000) >> 16;
            lAddressComponentArray[2] = (lTemp & 0x0000ff00) >> 8;
            lAddressComponentArray[3] = (lTemp & 0x000000ff);
        }
        return lAddressComponentArray;
    }    
    
    public void Done()
    {   // Done button was pressed - Close TCP connection if open, close sockets and exit
        try
        {
            m_ListenSock.close();      
        }
        catch (Exception Ex) 
        { 
        }
        System.exit(0);
    }
    
    public static void MessageBox(String sMessage, String sBoxTitle)
    {
        JOptionPane.showMessageDialog(null, sMessage, sBoxTitle, JOptionPane.INFORMATION_MESSAGE);
    }

    public void actionPerformed(ActionEvent e)
    {
        if(jButton_ClearLog == e.getSource())
        {   // Clear the Event Log text area
            jTextArea_EventLog.setText(null);
        }
        if(jButton_Done == e.getSource())
        {
             Done();
        }
    }
    
    private javax.swing.JPanel jPanel_EventList;
    private static javax.swing.JTextArea jTextArea_EventList;              
    private javax.swing.JScrollPane JScrollPane_EventList;
    private javax.swing.JPanel jPanel_EventLog;
    private static javax.swing.JTextArea jTextArea_EventLog;
    private javax.swing.JScrollPane JScrollPane_EventLog;
    private javax.swing.JPanel jPanel_Controls;
    private javax.swing.JButton jButton_ClearLog;
    private javax.swing.JButton jButton_Done;

    private void initComponents()
    {
        jPanel_EventList = new javax.swing.JPanel();
        jTextArea_EventList = new javax.swing.JTextArea();
        JScrollPane_EventList = new javax.swing.JScrollPane(jTextArea_EventList,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, 
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        jPanel_EventList.setPreferredSize(new Dimension(200, 400));
        jPanel_EventList.setBorder(javax.swing.BorderFactory.createTitledBorder("Event type list"));

        org.jdesktop.layout.GroupLayout jPanel1Layout = new org.jdesktop.layout.GroupLayout(jPanel_EventList);
        jPanel_EventList.setLayout(jPanel1Layout);
        jPanel1Layout.setHorizontalGroup(
            jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
            .add(JScrollPane_EventList));
        jPanel1Layout.setVerticalGroup(
            jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
            .add(JScrollPane_EventList));

        jPanel_EventLog = new javax.swing.JPanel();
        jTextArea_EventLog = new javax.swing.JTextArea();
        JScrollPane_EventLog = new javax.swing.JScrollPane(jTextArea_EventLog,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, 
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        jPanel_EventLog.setPreferredSize(new Dimension(300, 400));
        jPanel_EventLog.setBorder(javax.swing.BorderFactory.createTitledBorder("Event Log (Diagnostic)"));
        
        org.jdesktop.layout.GroupLayout jPanel2Layout = new org.jdesktop.layout.GroupLayout(jPanel_EventLog);
        jPanel_EventLog.setLayout(jPanel2Layout);
        jPanel2Layout.setHorizontalGroup(
            jPanel2Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
            .add(JScrollPane_EventLog));
        jPanel2Layout.setVerticalGroup(
            jPanel2Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
            .add(JScrollPane_EventLog));

        jPanel_Controls = new javax.swing.JPanel();
        jButton_ClearLog = new javax.swing.JButton();
        jButton_ClearLog.setText("Clear Log");
        jButton_ClearLog.addActionListener(this);
        jButton_Done = new javax.swing.JButton();
        jButton_Done.setText("Done");
        jButton_Done.addActionListener(this);
        jPanel_Controls.setPreferredSize(new Dimension(100, 100));
        jPanel_Controls.setBorder(javax.swing.BorderFactory.createTitledBorder("Controls"));

        org.jdesktop.layout.GroupLayout jPanel3Layout = new org.jdesktop.layout.GroupLayout(jPanel_Controls);
        jPanel_Controls.setLayout(jPanel3Layout);
        jPanel3Layout.setHorizontalGroup(
            jPanel3Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.CENTER)
            .add(jButton_ClearLog)
            .add(jButton_Done));
        jPanel3Layout.setVerticalGroup(
            jPanel3Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
            .add(jPanel3Layout.createSequentialGroup()
            .add(jButton_ClearLog)
            .add(jButton_Done)));
        jPanel3Layout.linkSize(new java.awt.Component[] {jButton_ClearLog, jButton_Done});

        org.jdesktop.layout.GroupLayout layout = new org.jdesktop.layout.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        getContentPane().setPreferredSize(new Dimension(640, 400));
         
        layout.setHorizontalGroup(
            layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
            .add(layout.createSequentialGroup()
            .add(jPanel_EventList)
            .add(jPanel_EventLog)
            .add(jPanel_Controls)));

        layout.setVerticalGroup(
            layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
            .add(jPanel_EventList)
            .add(jPanel_EventLog)
            .add(jPanel_Controls));

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("CaseStudy_ENS_Server");
        pack();
    }                                             
}