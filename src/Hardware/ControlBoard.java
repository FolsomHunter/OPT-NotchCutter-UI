/******************************************************************************
* Title: ControlBoard.java
* Author: Mike Schoonover
* Date: 5/24/09
*
* Purpose:
*
* This class interfaces with a NotchCutter unit.
*
* Open Source Policy:
*
* This source code is Public Domain and free to any interested party.  Any
* person, company, or organization may do with it as they please.
*
*/

//-----------------------------------------------------------------------------

package Hardware;

import model.IniFile;
import java.io.*;
import java.net.*;
import view.Log;

//-----------------------------------------------------------------------------
//-----------------------------------------------------------------------------
// class ControlBoard
//
// This class creates and handles an interface to a Control board.
//

public class ControlBoard extends Board{

    byte[] monitorBuffer;
    byte[] allEncoderValuesBuf;

    boolean udpResponseFlag = false;
    
    int packetRequestTimer = 0;

    int runtimePacketSize;

    int pktID;
    boolean reSynced;
    int reSyncCount = 0, reSyncPktID;
    int timeOutWFP = 0; //used by processDataPackets

    //Commands for Control boards
    //These should match the values in the code for those boards.

    static byte NO_ACTION = 0;
    static byte STOP_MODE_CMD = 1;
    static byte CUT_MODE_CMD = 2;
    static byte ZERO_DEPTH_CMD = 3;
    static byte ZERO_TARGET_DEPTH_CMD = 4;
    static byte GET_DATA_PACKET_CMD = 5;
    
    static byte ERROR = 125;
    static byte DEBUG_CMD = 126;
    static byte EXIT_CMD = 127;
    
    //Status Codes for Control boards
    //These should match the values in the code for those boards.

    static byte NO_STATUS = 0;

    static int MONITOR_PACKET_SIZE = 25;
    static int ALL_ENCODERS_PACKET_SIZE = 24;    
    static int RUNTIME_PACKET_SIZE = 2048;

//-----------------------------------------------------------------------------
// UTBoard::UTBoard (constructor)
//
// The parameter configFile is used to load configuration data.  The IniFile
// should already be opened and ready to access.
//

public ControlBoard(IniFile pConfigFile, String pBoardName, int pBoardIndex,
  int pRuntimePacketSize, boolean pSimulate, Log pLog)
{

    super(pLog);

    configFile = pConfigFile;
    boardName = pBoardName;
    boardIndex = pBoardIndex;
    runtimePacketSize = pRuntimePacketSize;
    simulate = pSimulate;

}//end of UTBoard::UTBoard (constructor)
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// ControlBoard::init
//
// Initializes new objects. Should be called immediately after instantiation.
//

public void init()
{

    monitorBuffer = new byte[MONITOR_PACKET_SIZE];
    
    allEncoderValuesBuf = new byte[ALL_ENCODERS_PACKET_SIZE];
    
    //read the configuration file and create/setup the charting/control elements
    configure(configFile);

}//end of ControlBoard::init
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// ControlBoard::configure
//
// Loads configuration settings from pConfigFile.
// The various child objects are then created as specified by the config data.
//

@Override
void configure(IniFile pConfigFile)
{

    super.configure(pConfigFile);
        
    inBuffer = new byte[RUNTIME_PACKET_SIZE];
    outBuffer = new byte[RUNTIME_PACKET_SIZE];

}//end of ControlBoard::configure
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// ControlBoard::run
//
// This thread connects with the board and then sleeps.  It cannot be killed
// or the socket will be closed.
//

@Override
public void run() {

    //link with all the remotes
    connect();

    //Since the sockets and associated streams were created by this
    //thread, it cannot be closed without disrupting the connections. If
    //other threads try to read from the socket after the thread which
    //created the socket finishes, an exception will be thrown.  This
    //thread just waits() after performing the connect function.  The
    //alternative is to close the socket and allow another thread to
    //reopen it, but this results in a lot of overhead.

    waitForever();

}//end of ControlBoard::run
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// ControlBoard::waitForever
//
// Puts the thread in wait mode forever.
//

public synchronized void waitForever()
{

    while (true){
        try{wait();}
        catch (InterruptedException e) { }
    }

}//end of ControlBoard::waitForever
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// ControlBoard::connect
//
// Opens a TCP/IP connection with the Control Board.
//

public synchronized void connect()
{

    if (ipAddrS == null || ipAddr == null){
        log.appendLine(
                "Control board #" + boardIndex + " never responded to "
                + "roll call and cannot be contacted.");
        return;
    }

    log.appendLine("Opening connection with Control board...");

    try {

        log.appendLine("Control Board IP Address: " + ipAddr.toString());

        if (!simulate) {
            socket = new Socket(ipAddr, 23);
        }
        else {

            ControlSimulator controlSimulator = 
                                            new ControlSimulator( ipAddr, 23);
            controlSimulator.init();
            
            socket = controlSimulator;
            
        }

        //set amount of time in milliseconds that a read from the socket will
        //wait for data - this prevents program lock up when no data is ready
        socket.setSoTimeout(250);

        out = new PrintWriter(socket.getOutputStream(), true);

        in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

        byteOut = new DataOutputStream(socket.getOutputStream());
        byteIn =  new DataInputStream(socket.getInputStream());

    }//try
    catch (IOException e) {
        logSevere(e.getMessage() + " - Error: 238");
        log.appendLine("Couldn't get I/O for " + ipAddrS);
        return;
    }

    try {
        //display the greeting message sent by the remote
        log.appendLine(ipAddrS + " says " + in.readLine());
    }
    catch(IOException e){
        logSevere(e.getMessage() + " - Error: 248");
    }

    //flag that board setup has been completed - whether it failed or not
    setupComplete = true;

    //flag that setup was successful and board is ready for use
    ready = true;


    log.appendLine("Control " + ipAddrS + " is ready.");

    notifyAll(); //wake up all threads that are waiting for this to complete

}//end of ControlBoard::connect
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// ControlBoard:initialize
//
// Sets up various settings on the board.
//

public void initialize()
{

}//end of ControlBoard::initialize
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// ControlBoard::processOneDataPacket
//
// This function processes a single data packet if it is available.  If
// pWaitForPkt is true, the function will wait until data is available.
//
// The amount of time the function is to wait for a packet is specified by
// pTimeOut.  Each count of pTimeOut equals 10 ms.
//
// This function should be called often to allow processing of data packets
// received from the remotes and stored in the socket buffer.
//
// All packets received from the remote devices should begin with
// 0xaa, 0x55, 0xbb, 0x66, followed by the packet identifier, the DSP chip
// identifier, and the DSP core identifier.
//
// Returns number of bytes retrieved from the socket, not including the
// 4 header bytes, the packet ID, the DSP chip ID, and the DSP core ID.
// Thus, if a non-zero value is returned, a packet was processed.  If zero
// is returned, some bytes may have been read but a packet was not successfully
// processed due to missing bytes or header corruption.
// A return value of -1 means that the buffer does not contain a packet.
//

@Override
public int processOneDataPacket(boolean pWaitForPkt, int pTimeOut)
{

    if (byteIn == null) {return -1;}  //do nothing if the port is closed

    try{

        //wait a while for a packet if parameter is true
        if (pWaitForPkt){
            timeOutWFP = 0;
            while(byteIn.available() < 5 && timeOutWFP++ < pTimeOut){
                waitSleep(10);
            }
        }

        //wait until 5 bytes are available - this should be the 4 header bytes,
        //and the packet identifier
        if (byteIn.available() < 5) {return -1;}

        //read the bytes in one at a time so that if an invalid byte is
        //encountered it won't corrupt the next valid sequence in the case
        //where it occurs within 3 bytes of the invalid byte

        //check each byte to see if the first four create a valid header
        //if not, jump to resync which deletes bytes until a valid first header
        //byte is reached

        //if the reSynced flag is true, the buffer has been resynced and an 0xaa
        //byte has already been read from the buffer so it shouldn't be read
        //again

        //after a resync, the function exits without processing any packets

        if (!reSynced){
            //look for the 0xaa byte unless buffer just resynced
            byteIn.read(inBuffer, 0, 1);
            if (inBuffer[0] != (byte)0xaa) {reSync(); return 0;}
        }
        else {reSynced = false;}

        byteIn.read(inBuffer, 0, 1);
        if (inBuffer[0] != (byte)0x55) {reSync(); return 0;}
        byteIn.read(inBuffer, 0, 1);
        if (inBuffer[0] != (byte)0xbb) {reSync(); return 0;}
        byteIn.read(inBuffer, 0, 1);
        if (inBuffer[0] != (byte)0x66) {reSync(); return 0;}

        //read in the packet identifier
        byteIn.read(inBuffer, 0, 1);

        //store the ID of the packet (the packet type)
        pktID = inBuffer[0];

        if (pktID == GET_DATA_PACKET_CMD) {return readBytes(2);}
        else
        if (pktID == CUT_MODE_CMD){return readBytes(2);}
        
        // add more commands here

    }
    catch(IOException e){
        logSevere(e.getMessage() + " - Error: 799");
    }

    return 0;

}//end of ControlBoard::processOneDataPacket
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// ControlBoard::reSync
//
// Clears bytes from the socket buffer until 0xaa byte reached which signals
// the *possible* start of a new valid packet header or until the buffer is
// empty.
//
// If an 0xaa byte is found, the flag reSynced is set true to that other
// functions will know that an 0xaa byte has already been removed from the
// stream, signalling the possible start of a new packet header.
//
// There is a special case where a 0xaa is found just before the valid 0xaa
// which starts a new packet - the first 0xaa is the last byte of the previous
// packet (usually the checksum).  In this case, the next packet will be lost
// as well.  This should happen rarely.
//

public void reSync()
{

    reSynced = false;

    //track the number of times this function is called, even if a resync is not
    //successful - this will track the number of sync errors
    reSyncCount++;

    //store info pertaining to what preceded the reSync - these values will be
    //overwritten by the next reSync, so they only reflect the last error
    //NOTE: when a reSync occurs, these values are left over from the PREVIOUS good
    // packet, so they indicate what PRECEDED the sync error.

    reSyncPktID = pktID;

    try{
        while (byteIn.available() > 0) {
            byteIn.read(inBuffer, 0, 1);
            if (inBuffer[0] == (byte)0xaa) {reSynced = true; break;}
        }
    }
    catch(IOException e){
        logSevere(e.getMessage() + " - Error: 847");
    }

}//end of ControlBoard::reSync
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// ControlBoard::driveSimulation
//
// Drive any simulation functions if they are active.  This function is usually
// called from a thread.
//

public void driveSimulation()
{

    if (simulate && socket != null) {
        ((ControlSimulator)socket).processDataPackets(false);
    }

}//end of ControlBoard::driveSimulation
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// ControlBoard::various get/set functions
//


//end of ControlBoard::various get/set functions
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// ControlBoard::getDataPacket
//
// This method sends a request to the remote unit for a data packet, waits
// for it, and then returns a reference to the data array.
//

public byte[] getDataPacket()
{

    return(null);
    
}//end of ControlBoard::getDataPacket
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// ControlBoard::invokeCutMode
//
// Sends "Cut" command to the remote.
//

public void invokeCutMode()
{
    
    sendBytes(CUT_MODE_CMD, (byte) 0);
    
}//end of ControlBoard::invokeCutMode
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// ControlBoard::invokeStopMode
//
// Sends "Stop" command to the remote.
//

public void invokeStopMode()
{

    sendBytes(STOP_MODE_CMD, (byte) 0);

}//end of ControlBoard::invokeStopMode
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// ControlBoard::zeroDepthCount
//
// Sends "Zero Depth Count" command to the remote.
//

public void zeroDepthCount()
{

    sendBytes(ZERO_DEPTH_CMD, (byte) 0);
    
}//end of ControlBoard::zeroDepthCount
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// ControlBoard::zeroTargetDepth
//
// Sends "Zero Target Depth " command to the remote.
//

public void zeroTargetDepth()
{

    sendBytes(ZERO_TARGET_DEPTH_CMD, (byte) 0);
    
}//end of ControlBoard::zeroDepthCount
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// ControlBoard::loadCalFile
//
// This loads the file used for storing calibration information such as cut
// depth, cut speed, cut aggression, etc.
//
// Each child object is passed a pointer to the file so that they may load their
// own data.
//

public void loadCalFile(model.IniFile pCalFile)
{

}//end of ControlBoard::loadCalFile
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// ControlBoard::saveCalFile
//
// This saves the file used for storing calibration information such as cut
// depth, cut speed, cut aggression, etc.
//
// Each child object is passed a pointer to the file so that they may load their
// own data.
//

public void saveCalFile(model.IniFile pCalFile)
{

}//end of ControlBoard::saveCalFile
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// ControlBoard::shutDown
//
// This function should be called before exiting the program.  Overriding the
// "finalize" method does not work as it does not get called reliably upon
// program exit.
//

protected void shutDown()
{

    //close everything - the order of closing may be important

    try{

        if (byteOut != null) {byteOut.close();}
        if (byteIn != null) {byteIn.close();}
        if (out != null) {out.close();}
        if (in != null) {in.close();}
        if (socket != null) {socket.close();}

    }
    catch(IOException e){
        logSevere(e.getMessage() + " - Error: 1009");
    }

}//end of ControlBoard::shutDown
//-----------------------------------------------------------------------------

}//end of class ControlBoard
//-----------------------------------------------------------------------------
//-----------------------------------------------------------------------------
