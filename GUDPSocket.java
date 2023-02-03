import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Date;
import java.util.Hashtable;
import java.util.Random;
import java.io.IOException;

public class GUDPSocket implements GUDPSocketAPI {

    DatagramSocket datagramSocket;
    volatile ArrayList<GUDPPacket> window = new ArrayList<GUDPPacket>();
    volatile ArrayList<GUDPPacket> buffer = new ArrayList<GUDPPacket>();
    volatile ArrayList<Long> timersList = new ArrayList<Long>();
    volatile ArrayList<Integer> seq_no_list = new ArrayList<Integer>();
    volatile Hashtable<Integer, Integer> tries_table = new Hashtable<Integer, Integer>();

    volatile int receiver_expected_seq = 0;

    Random random = new Random();

    volatile static int received = 0;

    PacketSender sender;
    volatile Boolean sender_thread_start = false;
    volatile boolean finished_sender = false;
    Thread sThread;

    PacketReciever reciever;
    volatile Boolean reciever_thread_start = false;
    Thread rThread;
    
    int bsn_base = random.nextInt(100);

    final int timeout = 200;
    final int timeout_max_tries = 10;

    volatile int ack_expected = bsn_base + 1 ;
    volatile ArrayList<GUDPPacket> to_resend = new ArrayList<GUDPPacket>();

    volatile int nxt_buffer_idx = 0;

    boolean failure = false;

    boolean start = true;

    public GUDPSocket(DatagramSocket socket) {
        datagramSocket = socket;
    }

    class PacketSender implements Runnable {
        public void run() {
            try {
                reciever = new PacketReciever();
                rThread = new Thread(reciever, "Reciever Thread");
                reciever_thread_start = true;
                rThread.start();
                Thread.sleep(200);
            } catch (Exception e) {
                System.out.println("Exception in reciever thread");
            }
            System.out.println("Sender Socket address: " + datagramSocket.getLocalPort());
            try {
                buffer.add(0 ,prepareBSN());
            } catch (IOException e1) {
                e1.printStackTrace();
            }
            while (sender_thread_start) {
                if (start) {
                    window.add(buffer.get(0));
                    window.add(buffer.get(1));
                    window.add(buffer.get(2));
                    nxt_buffer_idx += 3;
                    start = false;
                }

                if (!window.isEmpty()) {
                    for (int i = 0; i < window.size(); i++) {
                        try {
                            GUDPPacket gudpPacket = window.get(0);
                            Date now = new Date();
                            long pck_time = now.getTime();
                            if (!seq_no_check(gudpPacket.getSeqno())) {
                                timersList.add(pck_time);
                                seq_no_list.add(gudpPacket.getSeqno());
                            }
                            send_packet(gudpPacket);
                            ack_expected = gudpPacket.getSeqno() + 1;
                            if(gudpPacket.getType() == GUDPPacket.TYPE_BSN){
                                System.out.println("Sent packet: " + gudpPacket.getSeqno());
                            }else{
                                System.out.println("Sent packet: " + gudpPacket.getSeqno());
                            }
                            window.remove(0);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
                try {
                    Thread.sleep(20);
                    synchronized (seq_no_list) {
                        if (seq_no_list.size() > 0) {
                            for (int i = 0; i < seq_no_list.size(); i++) {
                                Date dNew = new Date();
                                long final_time = dNew.getTime();
                                synchronized (timersList) {
                                    if (timersList.size() != 0) {
                                        if(seq_no_list.get(i) + 3 < ack_expected){
                                            seq_no_list.remove(i);
                                            timersList.remove(i);
                                            continue;
                                        }
                                        if ((final_time - timersList.get(i)) > (long) timeout) {
                                            System.out.println("Timer expired for seq_num: " + seq_no_list.get(i));
                                            System.out.println("seq_no_lis: " + seq_no_list.toString());
                                            System.out.println("timeout_list: " + timersList.toString());
                                            GUDPPacket temp_pkt = get_from_buffer(seq_no_list.get(i));
                                            if (window.size() < GUDPPacket.MAX_WINDOW_SIZE) {
                                                System.out.println("ACK Expected: " + ack_expected);
                                                System.out.println("<--- Trying to retransmit pkt: " + temp_pkt.getSeqno());
                                                tries_table.putIfAbsent(temp_pkt.getSeqno(), 0);
                                                tries_table.put(temp_pkt.getSeqno(), tries_table.get(temp_pkt.getSeqno()) + 1);
                                                if(tries_table.get(temp_pkt.getSeqno()) >= timeout_max_tries){ // exceeded
                                                    seq_no_list.remove(seq_no_list.get(i));
                                                    timersList.remove(i);
                                                    System.out.println("---------- EXCEEDED TRIES FOR PACKET: " + temp_pkt.getSeqno());
                                                    sender_thread_start = false;
                                                    reciever_thread_start = false;
                                                    finished_sender = true;
                                                    failure = true;
                                                    break;
                                                }else{
                                                    seq_no_list.remove(seq_no_list.get(i));
                                                    timersList.remove(i);
                                                    window.add(temp_pkt);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        public void send_packet(GUDPPacket packet) throws InterruptedException, IOException {
            DatagramPacket udppacket;
            if(packet.getType() == GUDPPacket.TYPE_BSN){
                udppacket = new DatagramPacket(packet.getBytes(), GUDPPacket.HEADER_SIZE, packet.getSocketAddress());
            }else{
                udppacket = packet.pack();
            }
            try {
                datagramSocket.send(udppacket);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public GUDPPacket prepareBSN() throws IOException {
            byte[] buf = new byte[GUDPPacket.MAX_DATAGRAM_LEN];
            DatagramPacket udppacket = new DatagramPacket(buf, buf.length);
            ByteBuffer buf_temp = ByteBuffer.allocate(udppacket.getLength() + GUDPPacket.HEADER_SIZE);
            buf_temp.order(ByteOrder.BIG_ENDIAN);
            GUDPPacket gudpPacket = new GUDPPacket(buf_temp);
            gudpPacket.setType(GUDPPacket.TYPE_BSN);
            gudpPacket.setVersion(GUDPPacket.GUDP_VERSION);
            gudpPacket.setSeqno(bsn_base);
            gudpPacket.setSocketAddress(buffer.get(0).getSocketAddress());
            return gudpPacket;
        }
    }

    class PacketReciever implements Runnable {
        PacketReciever() {
            System.out.println("Reciever thread started: " + datagramSocket.getLocalPort());
        }

        public void run() {
            while (reciever_thread_start) {
                try {
                    final byte[] buf = new byte[GUDPPacket.MAX_DATAGRAM_LEN];
                    final DatagramPacket p = new DatagramPacket(buf, buf.length);
                    GUDPSocket.this.datagramSocket.receive(p);
                    GUDPPacket unpack = GUDPPacket.unpack(p);
                    if (unpack.getType() == 3) {
                        System.out.println("Received ACK: " + unpack.getSeqno());
                        if (unpack.getSeqno() != ack_expected && to_resend.size() < 3) {
                            to_resend.add(get_from_buffer(ack_expected - 1));
                        }else if(unpack.getSeqno() != ack_expected && to_resend.size() == 3 && window.size() == 0){
                            window.add(to_resend.get(0));
                            window.add(to_resend.get(1));
                            window.add(to_resend.get(2));
                            to_resend.clear();
                        }
                        else{
                            if (unpack.getSeqno() == buffer.size()  + bsn_base) {
                                sender_thread_start = false;
                                finished_sender = true;
                            }
                            for (int i = 0; i < seq_no_list.size(); i++) {
                                if (unpack.getSeqno() == seq_no_list.get(i) + 1) {
                                    Date dNew = new Date();
                                    long final_time = dNew.getTime();
                                    if ((final_time - timersList.get(i)) + 1 < (long) timeout) {
                                        System.out.println("stopped tracking: " + seq_no_list.get(i));
                                        synchronized (seq_no_list) {
                                            seq_no_list.remove(seq_no_list.get(i));
                                        }
                                        synchronized (timersList) {
                                            timersList.remove(i);
                                        }
                                        if (window.size() < GUDPPacket.MAX_WINDOW_SIZE
                                                && nxt_buffer_idx < buffer.size()) {
                                            System.out.println("->>>>>>>>>>>>>Added to window: " + buffer.get(nxt_buffer_idx).getSeqno());
                                            window.add(buffer.get(nxt_buffer_idx++));
                                        }
                                        break;
                                    }
                                }
                            }
                        }
                    }

                } catch (Exception ex) {
                }
            }
        }
    }

    public GUDPPacket get_from_buffer(int seq_num) {
        for (GUDPPacket e : buffer) {
            if (e.getSeqno() == seq_num) {
                return e;
            }
        }
        return null;
    }

    public boolean seq_no_check(int seq_no) {
        synchronized (seq_no_list) {
            for (Integer integer : seq_no_list) {
                if (integer == seq_no) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void send(DatagramPacket packet) throws IOException {
        if (!sender_thread_start) {
            sender = new PacketSender();
            sThread = new Thread(sender, "Sender Thread");
            sThread.start();
            sender_thread_start = true;
        }
        GUDPPacket gudppacket = GUDPPacket.encapsulate(packet);
        gudppacket.setVersion(GUDPPacket.GUDP_VERSION);
        gudppacket.setType(GUDPPacket.TYPE_DATA);
        gudppacket.setSeqno(buffer.size() + 1 + bsn_base);
        System.out.println("<--- Packet to send --->" + "Sq no: " + gudppacket.getSeqno());
        buffer.add(gudppacket);
    }

    public void sendAck(DatagramPacket packet, int seq_expected) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(packet.getLength() + GUDPPacket.HEADER_SIZE);
        buffer.order(ByteOrder.BIG_ENDIAN);
        GUDPPacket gudpPacket = new GUDPPacket(buffer);
        gudpPacket.setType(GUDPPacket.TYPE_ACK);
        gudpPacket.setVersion(GUDPPacket.GUDP_VERSION);
        gudpPacket.setSeqno(seq_expected);
        byte[] payload = intToByteArray(seq_expected);
        gudpPacket.setPayload(payload);
        gudpPacket.setSocketAddress((InetSocketAddress) packet.getSocketAddress());
        DatagramPacket uPacket = new DatagramPacket(gudpPacket.getBytes(),
                (GUDPPacket.HEADER_SIZE + gudpPacket.getPayloadLength()), packet.getSocketAddress());
        datagramSocket.send(uPacket);
        receiver_expected_seq = gudpPacket.getSeqno();
        System.out.println("ACK Packet sent: type->" + gudpPacket.getType() + " seq_no->" + gudpPacket.getSeqno() + " To: " + packet.getSocketAddress());
    }

    public static byte[] intToByteArray(int i) {
        byte[] result = new byte[4];
        result[0] = (byte) ((i >> 24) & 0xFF);
        result[1] = (byte) ((i >> 16) & 0xFF);
        result[2] = (byte) ((i >> 8) & 0xFF);
        result[3] = (byte) (i & 0xFF);
        return result;
    }

    @Override
    public void receive(DatagramPacket packet) throws IOException {
        byte[] buf = new byte[GUDPPacket.MAX_DATAGRAM_LEN];
        DatagramPacket udppacket = new DatagramPacket(buf, buf.length);
        datagramSocket.receive(udppacket);
        GUDPPacket gudppacket = GUDPPacket.unpack(udppacket);
        if(gudppacket.getType() == GUDPPacket.TYPE_BSN){
            System.out.println("Recieved BSN from " + gudppacket.getSocketAddress());
            sendAck(udppacket, gudppacket.getSeqno() + 1);
            received++;
            System.out.println("Received: " + GUDPSocket.received);
            datagramSocket.receive(udppacket);
            gudppacket = GUDPPacket.unpack(udppacket);
            if(receiver_expected_seq != gudppacket.getSeqno()){
                throw new IOException("Packet out of order");
            }
            System.out.println("Received packet: " + gudppacket.getSeqno());
            sendAck(udppacket, gudppacket.getSeqno() + 1);
            gudppacket.decapsulate(packet);
            received++;
        }else{
            if(receiver_expected_seq != gudppacket.getSeqno()){
                throw new IOException("Packet out of order");
            }
            gudppacket.decapsulate(packet);
            System.out.println("Received packet: " + gudppacket.getSeqno());
            sendAck(udppacket, gudppacket.getSeqno() + 1);
            received++;
        }
        System.out.println("Received: " + GUDPSocket.received);
    }

    @Override
    public void finish() throws IOException {

        while (!finished_sender) {
            try {
                Thread.sleep(3000L);
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }
        }
        finished_sender = false;
        buffer.clear();
        window.clear();
        seq_no_list.clear();
        timersList.clear();
        tries_table.clear();
        to_resend.clear();
        bsn_base = random.nextInt(100);
        nxt_buffer_idx = 0;
        ack_expected = 0;
        receiver_expected_seq = 0;
        received = 0;
        sender_thread_start = false;
        reciever_thread_start = false;
        start = true;
        if(!failure)
            System.out.println("<-- Finished One File -->");
    }

    @Override
    public void close() throws IOException {
        sender_thread_start = false;
        reciever_thread_start = false;
        datagramSocket.close();
        if(!failure)
            System.out.println("---------- FINISHED SENDING ----------");
        System.out.println("<-- Socket closed -->");
    }
}