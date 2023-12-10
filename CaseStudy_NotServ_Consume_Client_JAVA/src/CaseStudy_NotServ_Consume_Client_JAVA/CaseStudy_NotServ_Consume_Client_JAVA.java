/*	File			CaseStudy_NotServ_Publish_Client_Java.java
	Purpose			Notification Service Client  (Publisher)
	Author			Richard Anthony	(R.J.Anthony@gre.ac.uk)
	Date			September 2014
*/
package CaseStudy_NotServ_Consume_Client_JAVA;

import java.awt.Dimension;
import java.io.*;
import java.net.*;
import java.nio.*; // java.nio.ByteBuffer
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import javax.swing.JOptionPane;
import javax.swing.ScrollPaneConstants;

public class CaseStudy_NotServ_Consume_Client_JAVA extends javax.swing.JFrame implements ActionListener {

    private static int MAX_EVENT_TYPE_NAME_LENGTH = 50;
    private static int MAX_EVENT_VALUE_LENGTH = 50;
    private static Socket m_ENS_Socket;
    private static OutputStream m_ENS_OutStream;
    private static InputStream m_ENS_InStream;
    private static InetAddress m_ENS_IPAddress;
    private static boolean m_bSubscribed_Temperature;
    private static boolean m_bSubscribed_Humidity;
    
//struct DirectoryServiceReply {                        // Fixed, defined by Directory Service
//	// IP address followed by port
//	byte a1;
//	byte a2;
//	byte a3;
//	byte a4;
//	int iPort;
//	};
//
//struct EventNotificationService_Message_PDU {         // Fixed, defined by Event Notification Service
//	byte iMessageType;
//	char cEventType[MAX_EVENT_TYPE_NAME_LENGTH];
//	char cEventValue[MAX_EVENT_VALUE_LENGTH];       // Not valid in Subscribe messages
//	SOCKADDR_IN SendersAddress;
//      };    
    
    private static int EventNotificationService_Message_PDU_Size = 120;
    
    enum EventNotificationService_Message_Type {SubscribeToEvent, UnSubscribeToEvent, PublishEvent, EventUpdate};
        
    public CaseStudy_NotServ_Consume_Client_JAVA()
    {
        initComponents();
    }
    
    public static void main(String args[]) throws Exception
    {
        m_bSubscribed_Temperature = false;
        m_bSubscribed_Humidity = false;
        
        // Initialise the GUI libraries
        try {
            javax.swing.UIManager.LookAndFeelInfo[] installedLookAndFeels=javax.swing.UIManager.getInstalledLookAndFeels();
            for (int idx=0; idx<installedLookAndFeels.length; idx++)
                if ("Nimbus".equals(installedLookAndFeels[idx].getName())) {
                    javax.swing.UIManager.setLookAndFeel(installedLookAndFeels[idx].getClassName());
                    break;
                }
        }         
        catch (Exception Ex)
        {
            System.exit(0);
        }

        // Create and display the GUI form
        java.awt.EventQueue.invokeAndWait(new Runnable()
                { public void run() { new CaseStudy_NotServ_Consume_Client_JAVA().setVisible(true); } } );

        // Contact Directory Service (which is part of the Distributed Systems Workbench) to resolve the name of the ENS to its address
        // Note that the ENS server is self-registering with the Directory Service)
        String EVENT_NOTIFICATION_SERVER_NAME = "Event_NS";
        DirectoryServiceClient m_DirectoryServiceClient;
        System.out.println("started");
        m_DirectoryServiceClient = new DirectoryServiceClient();
        //m_DirectoryServiceClient.Resolve_Name_to_IP(EVENT_NOTIFICATION_SERVER_NAME, true);
        //m_ENS_IPAddress = m_DirectoryServiceClient.Get_ResolvedAddress();
        m_ENS_IPAddress = InetAddress.getByName("127.0.0.1");
        //int iENS_Port = m_DirectoryServiceClient.Get_Port();
        int iENS_Port = 8003;
        InetAddress Local_IPAddress = m_DirectoryServiceClient.Get_LocalAddress();

        jTextArea_EventLog.setText("Local Address: " + Local_IPAddress.getHostAddress() + "\n");
        jTextArea_EventLog.append("ENS Address: " + m_ENS_IPAddress.getHostAddress() + "\n");
        jTextArea_EventLog.append("ENS Port: " + iENS_Port + "\n");
       
        // Connection to ENS
        try
        {
            m_ENS_Socket = new Socket(m_ENS_IPAddress, iENS_Port); // Create Socket and connect to ENS
            m_ENS_OutStream = m_ENS_Socket.getOutputStream();
            m_ENS_InStream = m_ENS_Socket.getInputStream();            
            m_ENS_Socket.setSoTimeout(50); // Set timeout to 50ms to ensure the application remains responsive
                                           // the receive method will be called periodically using a timer
        }   catch (IOException Ex) 
        {   
            JOptionPane.showMessageDialog(null, "Could not connect to Event Notification Server ... Exiting",
                                            "Event Notification - Publish Client (Java)", 0);
            System.exit(-3);
        }
        
        // Receive update messages from ENS
        while (true)
        {   // The ENS Server sends Update messages when the value of any subscribed EventTypes changes
            Receive_Update_Message_From_ENS();
            Thread.sleep(200);
        }
    }
        
    private void Send_Subscribe_Message_To_ENS(String sEventType, boolean bSubscribe)   
    {   // Serialise an EventNotificationService_Message_Type PDU into a byte array
        // PDU format is:
        // Size     Type        Field name
        // 1        byte        iMessageType;
        // 50       char        cEventType[MAX_EVENT_TYPE_NAME_LENGTH];
        // 50       char        cEventValue[MAX_EVENT_VALUE_LENGTH];	// Not valid in Subscribe messages
        // 3        byte        Pad to place SOCKADDR_IN fields on 4-byte boundary
        // 16       SOCKADDR_IN SendersAddress;
        byte[] baENS_Request = new byte[EventNotificationService_Message_PDU_Size];
        ByteBuffer bbENS_Request_Wrapped = ByteBuffer.wrap(baENS_Request);

        byte[] baEventType = sEventType.getBytes();
        byte[] baENS_Address = m_ENS_IPAddress.getAddress();
        bbENS_Request_Wrapped.position(0);
        if(true == bSubscribe)
        {
            bbENS_Request_Wrapped.put((byte) EventNotificationService_Message_Type.SubscribeToEvent.ordinal());
        }
        else
         {
            bbENS_Request_Wrapped.put((byte) EventNotificationService_Message_Type.UnSubscribeToEvent.ordinal());
        }
       
        bbENS_Request_Wrapped.position(1);
        bbENS_Request_Wrapped.put(baEventType);
       
        // Only need to encode the IP Address portion of the C++ SOCKADDR_IN structure
        // Size     Type        Field name
        // 2        short       sin_family;
        // 2        ushort      sin_port;
        // 4        In_Addr     sin_addr;   // 4 Bytes  (offset is 4 bytes from start of structure)
        // 8        byte        sin_zero[8];
        bbENS_Request_Wrapped.position(108);
        bbENS_Request_Wrapped.put(baENS_Address);
 
        try
        {
            // Send ENS 'Subscribe / UnSubscribe message' request
            m_ENS_OutStream.write(baENS_Request);
            if(true == bSubscribe)
            {
                jTextArea_EventLog.append("Subscribed to  Event:" + sEventType + "\n");
            }
            else
             {
                jTextArea_EventLog.append("UnSubscribed to  Event:" + sEventType + "\n");
            }
           
        }   catch (IOException Ex) 
        {                   
        }
    }




    private static ArrayList<MessageQueueItem> messageQueue = new ArrayList<>();

    private static void Receive_Update_Message_From_ENS()
    {
        byte[] baReceiveData = new byte[1024];
        byte[] baEventType = new byte[MAX_EVENT_TYPE_NAME_LENGTH];
        byte[] baEventValue = new byte[MAX_EVENT_VALUE_LENGTH];
        try {
            // 从输入流中读取接收到的数据
            m_ENS_InStream.read(baReceiveData);

            // 使用 ByteBuffer 包装接收到的数据
            ByteBuffer bbReceiveData = ByteBuffer.wrap(baReceiveData);
            bbReceiveData.position(0);

            // 检查消息类型是否为 EventUpdate
            if (EventNotificationService_Message_Type.EventUpdate.ordinal() == bbReceiveData.get()) {
                bbReceiveData.position(1);

                // 从数据中提取事件类型
                bbReceiveData.get(baEventType, 0, MAX_EVENT_TYPE_NAME_LENGTH);
                String sEventType = ByteArray_To_String(baEventType);

                bbReceiveData.position(1 + MAX_EVENT_TYPE_NAME_LENGTH);

                // 从数据中提取事件值
                bbReceiveData.get(baEventValue, 0, MAX_EVENT_VALUE_LENGTH);
                String sEventValue = ByteArray_To_String(baEventValue);

                // 获取第101个字节
                byte orderByte = bbReceiveData.get(100);

                MessageQueueItem item = new MessageQueueItem(sEventType, sEventValue, orderByte & 0xFF);

                messageQueue.add(item);

                periodicalUpdate();

            }
        } catch (IOException Ex) {
            // 没有数据
        }
    }

    private static void periodicalUpdate(){

        Collections.sort(messageQueue, Comparator.comparingInt(MessageQueueItem::getOrderTime));

        for(MessageQueueItem item : messageQueue){
            // 将事件更新信息记录到日志
            jTextArea_EventLog.append("Update " + item.getSEventType() + " " + item.getSEventValue() + "\n");

            // 根据事件类型更新相应的界面元素
            if ("TemperatureChange".equals(item.getSEventType())) {
                JTextField_Temperature.setText(item.getSEventValue());
            }
            if ("HumidityChange".equals(item.getSEventType())) {
                JTextField_Humidity.setText(item.getSEventValue());
            }
        }

        messageQueue.clear();

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
    
    public void Done()
    {   // Done button was pressed - Close TCP connection if open, close sockets and exit
        try
        {
            m_ENS_Socket.shutdownInput();
            m_ENS_Socket.shutdownOutput();
            m_ENS_Socket.close();      
        }   catch (IOException Ex) 
        { 
        }
        System.exit(0);
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
        if(JButton_Temperature == e.getSource())
        {
             if(false == m_bSubscribed_Temperature)
             {   // Subscribe
                 m_bSubscribed_Temperature = true;
                 Send_Subscribe_Message_To_ENS("TemperatureChange", true);
                 JButton_Temperature.setText("UnSubscribe to Temperature Change Events");
             }
             else
             {   // UnSubscribe
                 m_bSubscribed_Temperature = false;
                 Send_Subscribe_Message_To_ENS("TemperatureChange", false);
                 JButton_Temperature.setText("Subscribe to Temperature Change Events");
                 JTextField_Temperature.setText("");
             }
        }
        if(JButton_Humidity == e.getSource())
        {
             if(false == m_bSubscribed_Humidity)
             {   // Subscribe
                 m_bSubscribed_Humidity = true;
                 Send_Subscribe_Message_To_ENS("HumidityChange", true);
                 JButton_Humidity.setText("UnSubscribe to Humidity Change Events");
             }
             else
             {   // UnSubscribe
                 m_bSubscribed_Humidity = false;
                 Send_Subscribe_Message_To_ENS("HumidityChange", false);
                 JButton_Humidity.setText("Subscribe to Humidity Change Events");
                 JTextField_Humidity.setText("");
             }
        }
        if(JButton_Group == e.getSource()){
            if(false == m_bSubscribed_Temperature)
            {   // Subscribe
                m_bSubscribed_Temperature = true;
                Send_Subscribe_Message_To_ENS("TemperatureChange", true);
                JButton_Temperature.setText("UnSubscribe to Temperature Change Events");
            }
            else
            {   // UnSubscribe
                m_bSubscribed_Temperature = false;
                Send_Subscribe_Message_To_ENS("TemperatureChange", false);
                JButton_Temperature.setText("Subscribe to Temperature Change Events");
                JTextField_Temperature.setText("");
            }
            if(false == m_bSubscribed_Humidity)
            {   // Subscribe
                m_bSubscribed_Humidity = true;
                Send_Subscribe_Message_To_ENS("HumidityChange", true);
                JButton_Humidity.setText("UnSubscribe to Humidity Change Events");
            }
            else
            {   // UnSubscribe
                m_bSubscribed_Humidity = false;
                Send_Subscribe_Message_To_ENS("HumidityChange", false);
                JButton_Humidity.setText("Subscribe to Humidity Change Events");
                JTextField_Humidity.setText("");
            }
        }
    }
    
    private javax.swing.JPanel jPanel_Controls;
    private javax.swing.JButton JButton_Temperature;
    private javax.swing.JLabel jLabel_Temperature;
    private static javax.swing.JTextField JTextField_Temperature;
    private javax.swing.JButton JButton_Humidity;
    private javax.swing.JLabel jLabel_Humidity;
    private javax.swing.JButton JButton_Group;
    private static javax.swing.JTextField JTextField_Humidity;
    private javax.swing.JButton jButton_ClearLog;
    private javax.swing.JButton jButton_Done;
    private javax.swing.JPanel jPanel_EventLog;
    private static javax.swing.JTextArea jTextArea_EventLog;              
    private javax.swing.JScrollPane JScrollPane_EventLog;

    private void initComponents()
    {
        jPanel_Controls = new javax.swing.JPanel();
        JButton_Group = new javax.swing.JButton();
        JButton_Group.setText("Subscribe to Change Group Events");
        JButton_Group.addActionListener(this);
        JButton_Temperature = new javax.swing.JButton();
        JButton_Temperature.setText("Subscribe to Temperature Change Events");
        JButton_Temperature.addActionListener(this);
        JButton_Temperature.setPreferredSize(new Dimension(280, 20));
        jLabel_Temperature = new javax.swing.JLabel();
        jLabel_Temperature.setText("Temperature");
        JTextField_Temperature = new javax.swing.JTextField();
        JTextField_Temperature.setPreferredSize(new Dimension(50, 20));
        JButton_Humidity = new javax.swing.JButton();
        JButton_Humidity.setText("Subscribe to Humidity Change Events");
        JButton_Humidity.addActionListener(this);
        jLabel_Humidity = new javax.swing.JLabel();
        jLabel_Humidity.setText("Humidity");
        JTextField_Humidity = new javax.swing.JTextField();
        jButton_ClearLog = new javax.swing.JButton();
        jButton_ClearLog.setText("Clear Log");
        jButton_ClearLog.addActionListener(this);
        jButton_Done = new javax.swing.JButton();
        jButton_Done.setText("Done");
        jButton_Done.addActionListener(this);
        jPanel_Controls.setPreferredSize(new Dimension(250, 260));
        jPanel_Controls.setBorder(javax.swing.BorderFactory.createTitledBorder("Controls"));
       
        org.jdesktop.layout.GroupLayout jPanel1Layout = new org.jdesktop.layout.GroupLayout(jPanel_Controls);
        jPanel_Controls.setLayout(jPanel1Layout);
        jPanel1Layout.setHorizontalGroup(
            jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.CENTER)
            .add(jPanel1Layout.createSequentialGroup()
            .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.TRAILING)
            .add(jLabel_Temperature)                    
            .add(jLabel_Humidity)
            .add(JTextField_Temperature)            
            .add(JTextField_Humidity)      
            .add(JButton_Temperature)
            .add(JButton_Humidity)))       
            .add(jButton_ClearLog)
            .add(jButton_Done));
       
        jPanel1Layout.setVerticalGroup(
            jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
            .add(jPanel1Layout.createSequentialGroup()
            .add(JButton_Temperature)
            .add(jLabel_Temperature)                   
            .add(JTextField_Temperature)      
            .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
            .add(JButton_Humidity)
            .add(jLabel_Humidity)
            .add(JTextField_Humidity)
            .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
            .add(jButton_ClearLog)
            .add(jButton_Done)));
        
        jPanel1Layout.linkSize(new java.awt.Component[] {JButton_Temperature, JButton_Humidity});
        jPanel1Layout.linkSize(new java.awt.Component[] {jButton_ClearLog, jButton_Done});
        jPanel1Layout.linkSize(new java.awt.Component[] {JTextField_Temperature, JTextField_Humidity});

        jPanel_EventLog = new javax.swing.JPanel();
        jTextArea_EventLog = new javax.swing.JTextArea();
        JScrollPane_EventLog = new javax.swing.JScrollPane(jTextArea_EventLog,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, 
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        jPanel_EventLog.setPreferredSize(new Dimension(270, 400));
        jPanel_EventLog.setBorder(javax.swing.BorderFactory.createTitledBorder("Event Log (Diagnostic)"));
         
        org.jdesktop.layout.GroupLayout jPanel2Layout = new org.jdesktop.layout.GroupLayout(jPanel_EventLog);
        jPanel_EventLog.setLayout(jPanel2Layout);
        jPanel2Layout.setHorizontalGroup(
            jPanel2Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
            .add(JScrollPane_EventLog));
        jPanel2Layout.setVerticalGroup(
            jPanel2Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
            .add(JScrollPane_EventLog));

        org.jdesktop.layout.GroupLayout layout = new org.jdesktop.layout.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        getContentPane().setPreferredSize(new Dimension(640, 400));
         
        layout.setHorizontalGroup(
        layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
            .add(layout.createSequentialGroup()
            .add(jPanel_Controls)
            .add(jPanel_EventLog)));
        layout.setVerticalGroup(
            layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
            .add(jPanel_Controls)
            .add(jPanel_EventLog));

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("CaseStudy_NotServ_Consume_Client_Java");
        pack();
    }                                        
}

class MessageQueueItem {
    private String sEventType;
    private String sEventValue;
    private int orderTime;

    public MessageQueueItem(String sEventType, String sEventValue, int orderTime){
        this.sEventType = sEventType;
        this.sEventValue = sEventValue;
        this.orderTime = orderTime;
    }

    public String getSEventType() {
        return sEventType;
    }

    public String getSEventValue() {
        return sEventValue;
    }

    public int getOrderTime() {
        return orderTime;
    }
}