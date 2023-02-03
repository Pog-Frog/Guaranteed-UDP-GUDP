# Guaranteed-UDP-GUDP
A guaranteed UDP project aims to provide a reliable connection over the UDP protocol
# Project goals:
  1.Enabling reliable transport over UDP
  2.Sliding window flow control
  3.Automatic repeat request (ARQ)
  4.Asynchronous communication
  
# GUDP-reliable-connection

- `GUDPSocket.java` Main work in this part

- To test the project you have to start the sender and the reciever, which simulates sending the data between two computers 

- You have to provide a .txt file that will be the message that the sender transmit to the reciever 

- Start the reciever first using the command: `VSRecv.java; java VSRecv -d  <Port number>`

- Start the sender using the command: `javac VSSend.java ; java VSSend -d 127.0.0.1:<Port number> <message.txt>`
