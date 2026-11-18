package main

import (
	"context"
	"encoding/binary"
	"io"
	"net/http"
	"os"
	"os/exec"
	"strconv"
	"syscall"
	"time"
)

func video(w http.ResponseWriter, r *http.Request) {
	select {
	case viewers <- struct{}{}:
		defer func() { <-viewers }()
	default:
		http.Error(w, "Viewer limit reached", 429)
		return
	}
	id := monitorID.Load()
	if id <= 0 {
		http.Error(w, "Desktop unavailable", 503)
		return
	}
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	cmd := exec.CommandContext(ctx, "/system/bin/app_process", "/", "DexVideo", strconv.Itoa(int(id)))
	cmd.Env = append(os.Environ(), "CLASSPATH=/data/local/tmp/galaxy-lan-desktop/DexVideo.jar")
	cmd.SysProcAttr = &syscall.SysProcAttr{Pdeathsig: syscall.SIGKILL}
	cmd.Stderr = os.Stderr
	pipe, err := cmd.StdoutPipe()
	if err != nil {
		http.Error(w, "Encoder unavailable", 503)
		return
	}
	if err = cmd.Start(); err != nil {
		http.Error(w, "Encoder unavailable", 503)
		return
	}
	defer func() { cancel(); cmd.Wait() }()
	w.Header().Set("Content-Type", "application/octet-stream")
	control := http.NewResponseController(w)
	header := make([]byte, 16)
	for {
		if _, err = io.ReadFull(pipe, header); err != nil {
			return
		}
		size := binary.BigEndian.Uint32(header)
		if size == 0 || size > 8<<20 {
			return
		}
		control.SetWriteDeadline(time.Now().Add(3 * time.Second))
		if _, err = w.Write(header); err != nil {
			return
		}
		if _, err = io.CopyN(w, pipe, int64(size)); err != nil {
			return
		}
		if control.Flush() != nil {
			return
		}
		control.SetWriteDeadline(time.Time{})
	}
}
