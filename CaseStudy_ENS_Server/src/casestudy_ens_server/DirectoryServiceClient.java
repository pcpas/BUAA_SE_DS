/*	File			DirectoryServiceClient.java
	Purpose			Directory Service Client - class to handle interfacing to DS
	Author			Richard Anthony	(R.J.Anthony@gre.ac.uk)
	Date			September 2014
*/
package casestudy_ens_server;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.swing.JOptionPane;

public class DirectoryServiceClient {
  
    private int From_DIRECTORY_SERVICE_PORT = 8002;
    private int To_DIRECTORY_SERVICE_PORT = 8008;
    private boolean m_bInitialised;
    private int m_iPort;
    private int[] m_iaAddress;
    InetAddress m_Local_IPAddress;
    InetAddress m_Broadcast_IPAddress;
    InetAddress m_Resolved_IPAddress;

    //struct DirectoryServiceReply {        // Fixed, defined by Directory Service
    // IP address followed by port
    //	byte a1;
    //	byte a2;
    //	byte a3;
    //	byte a4;
    //	int iPort;
    //	};

    DirectoryServiceClient()
    {
       m_bInitialised = false;
       m_iaAddress = new int[4];
       try
       {
           m_Local_IPAddress = InetAddress.getLocalHost();
           //m_Broadcast_IPAddress = GetLocalBroadcastAddress_From_LocalHostAddress(m_Local_IPAddress);
           m_Broadcast_IPAddress = InetAddress.getByName("255.255.255.255");
       }   
       catch (Exception Ex) 
       {             
           JOptionPane.showMessageDialog(null,"Could not get local address or broadcast address ... Exiting","Event Notification - Publish Client (Java)",0);
           System.exit(-1);
       }
    }
    
    void Resolve_Name_to_IP(String sServiceName_to_Resolve, boolean bShowDiagnosticInfo)
    {
       try
       {
            MulticastSocket ResolveSocket = new MulticastSocket(From_DIRECTORY_SERVICE_PORT);
            ResolveSocket.setSoTimeout(500); // Set timeout to 500ms so it will not wait indefinitely if the Directory Service does not reply
            byte[] baSendData = new byte[1024];
            byte[] baReceiveData = new byte[1024];
            String sSendString = "RESOLVE:" + sServiceName_to_Resolve;
            
            if(true == bShowDiagnosticInfo)
            {
                System.out.println("Request message: " + sSendString + "  'to DS' port:" + To_DIRECTORY_SERVICE_PORT + "  'from DS' port:" + From_DIRECTORY_SERVICE_PORT);
            }
            baSendData = sSendString.getBytes();
            DatagramPacket sendPacket = new DatagramPacket(baSendData, baSendData.length, m_Broadcast_IPAddress, To_DIRECTORY_SERVICE_PORT);
            ResolveSocket.setBroadcast(true);
            ResolveSocket.send(sendPacket);
            DatagramPacket receivePacket = new DatagramPacket(baReceiveData, baReceiveData.length);
            try
            {
            ResolveSocket.receive(receivePacket);
            }   catch (InterruptedIOException Ex) 
            {  // receive timed out - Directory service did not respond (either not present, or does not know the 'Event_NS' server)           
               JOptionPane.showMessageDialog(null,"Could not contact Directory Service... Exiting","Event Notification - Publish Client (Java)",0);
               System.exit(-2);
            }

            ByteBuffer bbWrapped = ByteBuffer.wrap(baReceiveData); // big-endian by default
            m_iaAddress[0] = (int) baReceiveData[0] & 0xff; //byte is -128 to 127 i.e. intrinsically signed - cast to larger variable to remove sign
            m_iaAddress[1] = (int) baReceiveData[1] & 0xff;
            m_iaAddress[2] = (int) baReceiveData[2] & 0xff;
            m_iaAddress[3] = (int) baReceiveData[3] & 0xff;

            bbWrapped = ByteBuffer.wrap(baReceiveData, 4/*offset*/, 4/*length*/); // big-endian by default
            bbWrapped.order(ByteOrder.LITTLE_ENDIAN); // big-endian by default, re-ordering is the equivallent of C++ ntofs() 
            m_iPort = bbWrapped.getInt();

            ResolveSocket.close();
            m_bInitialised = true;

        }   
        catch (IOException Ex) 
        {             
            JOptionPane.showMessageDialog(null,"Could not contact Directory Service... Exiting","Event Notification - Publish Client (Java)",0);
            System.exit(-3);
        }
    }
    
    InetAddress Get_LocalAddress()
    {
        return m_Local_IPAddress;
    }

    InetAddress Get_ResolvedAddress()
    {
       if(false == m_bInitialised)
       {    // return null address
            m_iaAddress[0] = m_iaAddress[1] = m_iaAddress[2] = m_iaAddress[3] = 0;
       }
       
       String sResolvedAddress = m_iaAddress[0] + "." + m_iaAddress[1] + "." + m_iaAddress[2] + "." + m_iaAddress[3];
       
       try
       {
           m_Resolved_IPAddress = InetAddress.getByName(sResolvedAddress);
       }   catch (IOException Ex) 
       {
           JOptionPane.showMessageDialog(null,"Could not get resolved address... Exiting","Event Notification - Publish Client (Java)",0);
           System.exit(-4);
       }                   
       return m_Resolved_IPAddress;
    }
    
    int Get_Port()
    {
       if(false == m_bInitialised)
       {    // return null port
            m_iPort = 0;
       }
       return m_iPort;
    }
    
    void Register_ServerName_with_DS(String sServiceName_to_Register, int iPort, boolean bShowDiagnosticInfo)
    {
       try
       {
            MulticastSocket RegisterSocket = new MulticastSocket(From_DIRECTORY_SERVICE_PORT);
            RegisterSocket.setSoTimeout(500); // Set timeout to 500ms so it will not wait indefinitely if the Directory Service does not reply
            // When registering a service name, no reply is expected from the Directory Service
            
            byte[] baSendData = new byte[1024];
            String sSendString = "REGISTER:" + sServiceName_to_Register + ":" + iPort;
            
            if(true == bShowDiagnosticInfo)
            {   // Uncomment the following line if required for diagnostic purposes
                //System.out.println("Request message: " + sSendString + "  'to DS' port:" + To_DIRECTORY_SERVICE_PORT + "  'from DS' port:" + From_DIRECTORY_SERVICE_PORT);
            }
            System.out.println(m_Local_IPAddress.getHostAddress());
            baSendData = sSendString.getBytes();
            DatagramPacket sendPacket = new DatagramPacket(baSendData, baSendData.length, m_Broadcast_IPAddress, To_DIRECTORY_SERVICE_PORT);
            RegisterSocket.setBroadcast(true);
            RegisterSocket.send(sendPacket);
 
            RegisterSocket.close();
        }   
        catch (IOException Ex) 
        {             
        }
    }
    
    InetAddress GetLocalBroadcastAddress_From_LocalHostAddress(InetAddress LocalAddress) throws Exception
    {   // Assumes IPv4 address
        try
        {
            byte[] bLocalBroadcast = LocalAddress.getAddress(); // Initialise byte array with local host's address (e.g. 192.168.100.6)
            // Set the host portion of the address to the broadcast value, depending on the address class
            // Class A from 0.0.0.0 to 127.255.255.255   will be converted to the broadcast address x.255.255.255 
            // Class B from 128.0.0.0 to 191.255.255.255   will be converted to the broadcast address x.x.255.255  
            // Class C 192.0.0.0 to 223.255.255.255   will be converted to the broadcast address x.x.x.255 
            // Class D and E (224.0.0.0 to 225.255.255.254)   will be converted to the broadcast address 255.255.255.255 
            
            // For comparison purposes, the first byte of the address is converted to an int value (to ensure operations are performed 'unsigned')
            int iMostSignificantAddrsssByte = (int) bLocalBroadcast[0] & 0xFF;
            if(iMostSignificantAddrsssByte >= 0 && iMostSignificantAddrsssByte <= 127)
            {   // Class A address
                bLocalBroadcast[1] = (byte)255;
                bLocalBroadcast[2] = (byte)255;
                bLocalBroadcast[3] = (byte)255;
            }
            if(iMostSignificantAddrsssByte >= 128 && iMostSignificantAddrsssByte <= 191)
            {   // Class B address
                bLocalBroadcast[2] = (byte)255;
                bLocalBroadcast[3] = (byte)255;
            }
            if(iMostSignificantAddrsssByte >= 192 && iMostSignificantAddrsssByte <= 223)
            {   // Class C address
                bLocalBroadcast[3] = (byte)255;
            }
            if(iMostSignificantAddrsssByte >= 224)
            {   // Class D / E address
                bLocalBroadcast[0] = (byte)255;
                bLocalBroadcast[1] = (byte)255;
                bLocalBroadcast[2] = (byte)255;
                bLocalBroadcast[3] = (byte)255;
            }
            InetAddress LocalBroadcastAddress = InetAddress.getByAddress(bLocalBroadcast);
            return LocalBroadcastAddress;
        }
        catch (Exception Ex)
        {
            throw(Ex);
        }
    }
}
