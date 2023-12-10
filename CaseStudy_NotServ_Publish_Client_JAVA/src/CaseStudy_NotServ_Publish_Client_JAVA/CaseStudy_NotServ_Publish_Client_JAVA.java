/*	File			CaseStudy_NotServ_Publish_Client_Java.java
	Purpose			Notification Service Client  (Publisher)
	Author			Richard Anthony	(R.J.Anthony@gre.ac.uk)
	Date			September 2014
*/
package CaseStudy_NotServ_Publish_Client_JAVA;

import java.awt.Dimension;
import java.io.*;
import java.net.*;
import java.nio.*; // java.nio.ByteBuffer
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.JOptionPane;
import javax.swing.ScrollPaneConstants;

public class CaseStudy_NotServ_Publish_Client_JAVA extends javax.swing.JFrame implements ActionListener, ChangeListener {

    private static int MAX_EVENT_TYPE_NAME_LENGTH = 50;
    private static int MAX_EVENT_VALUE_LENGTH = 50;
    private static Socket m_ENS_Socket;
    private static OutputStream m_ENS_OutStream;
    private static InetAddress m_ENS_IPAddress;
    private static int m_iTemperaturePrevious;
    private static int m_iHumidityPrevious;

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
        
    public CaseStudy_NotServ_Publish_Client_JAVA()
    {
        initComponents();
    }
    
    public static void main(String args[]) throws Exception
    {
      m_iTemperaturePrevious = 0;
      m_iHumidityPrevious = 0;

      // Initialise the GUI libraries
      try {
        javax.swing.UIManager.LookAndFeelInfo[] installedLookAndFeels = javax.swing.UIManager
            .getInstalledLookAndFeels();
        for (int idx = 0; idx < installedLookAndFeels.length; idx++)
          if ("Nimbus".equals(installedLookAndFeels[idx].getName())) {
            javax.swing.UIManager.setLookAndFeel(installedLookAndFeels[idx].getClassName());
            break;
          }
      } catch (Exception Ex) {
        System.exit(0);
      }

      // Create and display the GUI form
      java.awt.EventQueue.invokeAndWait(new Runnable() {
        public void run() {
          new CaseStudy_NotServ_Publish_Client_JAVA().setVisible(true);
        }
      });

      // Contact Directory Service (which is part of the Distributed Systems Workbench) to resolve the name of the ENS to its address
      // Note that the ENS server is self-registering with the Directory Service)
      String EVENT_NOTIFICATION_SERVER_NAME = "Event_NS";
      DirectoryServiceClient m_DirectoryServiceClient;
      System.out.println("started");
      m_DirectoryServiceClient = new DirectoryServiceClient();
      //m_DirectoryServiceClient.Resolve_Name_to_IP(EVENT_NOTIFICATION_SERVER_NAME, true);
      //m_ENS_IPAddress = m_DirectoryServiceClient.Get_ResolvedAddress();
      m_ENS_IPAddress = InetAddress.getByName("127.0.0.1");
      int iENS_Port = 8003;
      //int iENS_Port = m_DirectoryServiceClient.Get_Port();
      InetAddress Local_IPAddress = m_DirectoryServiceClient.Get_LocalAddress();

      jTextArea_EventLog.setText("Local Address: " + Local_IPAddress.getHostAddress() + "\n");
      jTextArea_EventLog.append("ENS Address: " + m_ENS_IPAddress.getHostAddress() + "\n");
      jTextArea_EventLog.append("ENS Port: " + iENS_Port + "\n");

      // Connection to ENS
      try {
        m_ENS_Socket = new Socket(m_ENS_IPAddress, iENS_Port); // Create Socket and connect to ENS
        m_ENS_OutStream = m_ENS_Socket.getOutputStream();
      } catch (IOException Ex) {
        JOptionPane.showMessageDialog(null, "Could not connect to Event Notification Server ... Exiting",
            "Event Notification - Publish Client (Java)", 0);
        System.exit(-3);
      }
    }
    
    private byte orderByte = 0;
        
    private void Send_Publish_Message_To_ENS(String sEventType, String sEventValue)   
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
        byte[] baEventValue = sEventValue.getBytes();
        byte[] baENS_Address = m_ENS_IPAddress.getAddress();
        bbENS_Request_Wrapped.position(0);
        bbENS_Request_Wrapped.put((byte) EventNotificationService_Message_Type.PublishEvent.ordinal());      
        bbENS_Request_Wrapped.position(1);
        bbENS_Request_Wrapped.put(baEventType);
        bbENS_Request_Wrapped.position(1+MAX_EVENT_TYPE_NAME_LENGTH);
        bbENS_Request_Wrapped.put(baEventValue);
       
        orderByte = (byte) ((orderByte + 1) % 256);

        bbENS_Request_Wrapped.position(101);
        bbENS_Request_Wrapped.put(orderByte);

        bbENS_Request_Wrapped.put((byte) 0); // 第一个 padding 字节
        bbENS_Request_Wrapped.put((byte) 0); // 第二个 padding 字节

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
            // Send ENS 'publish message' request
            m_ENS_OutStream.write(baENS_Request);
            jTextArea_EventLog.append("Published  Event:" + sEventType + "  Value:" + sEventValue + "\n");
        }   catch (IOException Ex) 
        {                   
        }
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
    }

    public void stateChanged(ChangeEvent e)
    {
        JSlider source = (JSlider)e.getSource();
        String sEventType = "";
        int iCurrentValue;
        if(source.equals(jSlider_Temperature))
        {
            iCurrentValue = (int)source.getValue();
            if(m_iTemperaturePrevious != iCurrentValue)
            {   // Only send message if value has changed
                m_iTemperaturePrevious = iCurrentValue; 
                sEventType = "TemperatureChange";
                String sEventValue = "" + iCurrentValue;
                Send_Publish_Message_To_ENS(sEventType, sEventValue);
                try
                {
                    Thread.sleep(200); // Limit rate at which messages are generated when user slides slider rapidly
                } catch (InterruptedException Ex)
                {
                }
            }
        }
        if(source.equals(jSlider_Humidity))
        {
            iCurrentValue = (int)source.getValue();
            if(m_iHumidityPrevious != iCurrentValue)
            {   // Only send message if value has changed
                m_iHumidityPrevious = iCurrentValue; 
                sEventType = "HumidityChange";
                String sEventValue = "" + iCurrentValue;
                Send_Publish_Message_To_ENS(sEventType, sEventValue);
                try
                {
                    Thread.sleep(200); // Limit rate at which messages are generated when user slides slider rapidly
                } catch (InterruptedException name)
                {
                }
            }
        }
    }

    private javax.swing.JPanel jPanel_Controls;
    private javax.swing.JLabel jLabel_Temperature;
    private javax.swing.JSlider jSlider_Temperature;
    private javax.swing.JLabel jLabel_Humidity;
    private javax.swing.JSlider jSlider_Humidity;
    private javax.swing.JButton jButton_ClearLog;
    private javax.swing.JButton jButton_Done;
    private javax.swing.JPanel jPanel_EventLog;
    private static javax.swing.JTextArea jTextArea_EventLog;              
    private javax.swing.JScrollPane JScrollPane_EventLog;

    private void initComponents() {
        jPanel_Controls = new javax.swing.JPanel();
        jLabel_Temperature = new javax.swing.JLabel();
        jSlider_Temperature = new javax.swing.JSlider();
        jLabel_Temperature.setText("0          Temperature          100");
        jSlider_Temperature.setMinimum(0);
        jSlider_Temperature.setMaximum(100);
        jSlider_Temperature.setValue(50);
        jSlider_Temperature.addChangeListener(this);
        jLabel_Humidity = new javax.swing.JLabel();
        jSlider_Humidity = new javax.swing.JSlider();
        jLabel_Humidity.setText("0              Humidity              100");
        jSlider_Humidity.setMinimum(0);
        jSlider_Humidity.setMaximum(100);
        jSlider_Humidity.setValue(50);
        jSlider_Humidity.addChangeListener(this);
        jButton_ClearLog = new javax.swing.JButton();
        jButton_ClearLog.setText("Clear Log");
        jButton_ClearLog.addActionListener(this);
        jButton_Done = new javax.swing.JButton();
        jButton_Done.setText("Done");
        jButton_Done.addActionListener(this);
        jPanel_Controls.setPreferredSize(new Dimension(120, 200));
        jPanel_Controls.setBorder(javax.swing.BorderFactory.createTitledBorder("Controls"));
        
        org.jdesktop.layout.GroupLayout jPanel1Layout = new org.jdesktop.layout.GroupLayout(jPanel_Controls);
        jPanel_Controls.setLayout(jPanel1Layout);
        jPanel1Layout.setHorizontalGroup(
            jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.CENTER)
            .add(jPanel1Layout.createSequentialGroup()
            .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.TRAILING)
            .add(jLabel_Temperature)                    
            .add(jLabel_Humidity)
            .add(jSlider_Humidity)
            .add(jSlider_Temperature)))     
            .add(jButton_ClearLog)
            .add(jButton_Done));
        jPanel1Layout.setVerticalGroup(
            jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
            .add(jPanel1Layout.createSequentialGroup()
            .add(jLabel_Temperature)
            .add(jSlider_Temperature)
            .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
            .add(jLabel_Humidity)
            .add(jSlider_Humidity)
            .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
            .add(jButton_ClearLog)
            .add(jButton_Done)));
        
        jPanel1Layout.linkSize(new java.awt.Component[] {jButton_ClearLog, jButton_Done});
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
        getContentPane().setPreferredSize(new Dimension(530, 400));
         
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
        setTitle("CaseStudy_NotServ_Publish_Client_Java");
        pack();
    }                     
}