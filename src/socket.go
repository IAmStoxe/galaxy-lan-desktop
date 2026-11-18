package main

import (
	"encoding/binary"
	"errors"
	"io"
	"net/http"
	"os"
	"os/exec"
	"strconv"
	"syscall"
	"time"

	"golang.org/x/net/websocket"
)

func inputSocket(w http.ResponseWriter, r *http.Request) {
	id := monitorID.Load()
	if id <= 0 || r.URL.Query().Get("display") != strconv.Itoa(int(id)) {
		http.Error(w, "Desktop changed", 409)
		return
	}
	server := websocket.Server{
		Handshake: func(_ *websocket.Config, req *http.Request) error {
			if req.Header.Get("Origin") != "https://"+req.Host {
				return errors.New("invalid origin")
			}
			return nil
		},
		Handler: func(ws *websocket.Conn) {
			defer ws.Close()
			ws.MaxPayloadBytes = 256
			cmd := exec.CommandContext(r.Context(), "/system/bin/app_process", "/", "DexInput", strconv.Itoa(int(id)))
			cmd.Env = append(os.Environ(), "CLASSPATH=/data/local/tmp/galaxy-lan-desktop/DexInput.jar")
			cmd.SysProcAttr = &syscall.SysProcAttr{Pdeathsig: syscall.SIGKILL}
			cmd.Stderr = os.Stderr
			stdin, err := cmd.StdinPipe()
			if err != nil {
				return
			}
			stdout, err := cmd.StdoutPipe()
			if err != nil {
				return
			}
			if err = cmd.Start(); err != nil {
				return
			}
			defer func() { stdin.Close(); cmd.Wait() }()
			finished := make(chan struct{})
			defer close(finished)
			go func() {
				select {
				case <-r.Context().Done():
					ws.Close()
				case <-finished:
				}
			}()
			ack := make([]byte, 1)
			if _, err = io.ReadFull(stdout, ack); err != nil {
				return
			}
			if err = websocket.Message.Send(ws, "ready"); err != nil {
				return
			}
			for {
				var packet []byte
				if err = websocket.Message.Receive(ws, &packet); err != nil {
					return
				}
				if monitorID.Load() != id || len(packet) != 7 {
					return
				}
				kind := packet[0]
				x := binary.BigEndian.Uint16(packet[1:])
				y := binary.BigEndian.Uint16(packet[3:])
				key := binary.BigEndian.Uint16(packet[5:])
				if kind > 7 || x >= 1920 || y >= 1080 || (kind == 6 && key > 16) {
					return
				}
				if kind == 4 {
					switch key {
					case 4, 19, 20, 21, 22, 61, 62, 66, 67, 111, 112:
					default:
						return
					}
				}
				if kind == 5 && (key < 32 || key > 126) {
					return
				}
				if _, err = stdin.Write(packet); err != nil {
					return
				}
				if _, err = io.ReadFull(stdout, ack); err != nil {
					return
				}
				ws.SetWriteDeadline(time.Now().Add(3 * time.Second))
				if err = websocket.Message.Send(ws, ack); err != nil {
					return
				}
			}
		},
	}
	server.ServeHTTP(w, r)
}
