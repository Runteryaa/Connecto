import socket
import struct
def read_varint(sock):
    val = 0
    pos = 0
    while True:
        b = sock.recv(1)[0]
        val |= (b & 0x7F) << pos
        if (b & 0x80) == 0:
            break
        pos += 7
    return val

def write_varint(sock, val):
    while True:
        temp = val & 0x7F
        val >>= 7
        if val != 0:
            temp |= 0x80
        sock.send(bytes([temp]))
        if val == 0:
            break

def pack_varint(val):
    b = bytearray()
    while True:
        temp = val & 0x7F
        val >>= 7
        if val != 0:
            temp |= 0x80
        b.append(temp)
        if val == 0:
            break
    return bytes(b)

sock = socket.socket()
sock.connect(('127.0.0.1', 43519))
# handshake
hs = pack_varint(0) + pack_varint(775) + pack_varint(9) + b'127.0.0.1' + struct.pack('>H', 43519) + pack_varint(2)
write_varint(sock, len(hs))
sock.send(hs)
# login start
ls = pack_varint(0) + pack_varint(4) + b'test' + bytes(16)
write_varint(sock, len(ls))
sock.send(ls)

# read login phase
while True:
    try:
        length = read_varint(sock)
        data = sock.recv(length)
        if data[0] == 3: # set compression
            print('compression set')
            pass
        elif data[0] == 2: # login success
            print('login success')
            # send login ack
            la = pack_varint(3)
            write_varint(sock, len(la))
            sock.send(la)
            
            # send client info
            ci = pack_varint(0) + pack_varint(5) + b'en_us' + bytes([10]) + pack_varint(0) + bytes([1, 127]) + pack_varint(1) + bytes([0, 1]) + pack_varint(0)
            write_varint(sock, len(ci))
            sock.send(ci)
            break
    except Exception as e:
        print(e)
        break

# read config phase
while True:
    try:
        length = read_varint(sock)
        data = sock.recv(length)
        pid = data[0]
        print(f'Config packet ID 0x{pid:x} len {length}')
        if pid == 0x05: # ping
            pong = pack_varint(4) + data[1:] # send pong?
            # write_varint(sock, len(pong))
            # sock.send(pong)
    except Exception as e:
        print('err', e)
        break
