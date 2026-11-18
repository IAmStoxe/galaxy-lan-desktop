package main

import (
	"bytes"
	"context"
	"crypto/subtle"
	_ "embed"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"image/jpeg"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"strconv"
	"sync/atomic"
	"time"
)

//go:embed index.html
var page []byte
var monitorID atomic.Int32
var viewers = make(chan struct{}, 2)
var keyCodes = map[string]string{"Enter": "66", "Escape": "111", "Backspace": "67", "Tab": "61", "Delete": "112", "ArrowUp": "19", "ArrowDown": "20", "ArrowLeft": "21", "ArrowRight": "22", " ": "62", "Back": "4"}

func display(ctx context.Context) ([]string, error) {
	id := monitorID.Load()
	if id <= 0 {
		return nil, fmt.Errorf("Virtual desktop unavailable")
	}
	return []string{"", strconv.Itoa(int(id)), "1920", "1080"}, nil
}

func stream(w http.ResponseWriter, r *http.Request) {
	select {
	case viewers <- struct{}{}:
		defer func() { <-viewers }()
	default:
		http.Error(w, "Viewer limit reached", 429)
		return
	}
	m, err := display(r.Context())
	if err != nil {
		http.Error(w, err.Error(), 503)
		return
	}
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	cmd := exec.CommandContext(ctx, "/system/bin/app_process", "/", "DexFrame", m[1])
	cmd.Env = append(os.Environ(), "CLASSPATH=/data/local/tmp/galaxy-lan-desktop/DexFrame.jar")
	pipe, err := cmd.StdoutPipe()
	if err != nil {
		http.Error(w, "Capture unavailable", 503)
		return
	}
	if err = cmd.Start(); err != nil {
		http.Error(w, "Capture unavailable", 503)
		return
	}
	defer func() { cancel(); cmd.Wait() }()
	w.Header().Set("Content-Type", "multipart/x-mixed-replace; boundary=dexframe")
	for {
		var n uint32
		if binary.Read(pipe, binary.BigEndian, &n) != nil || n == 0 || n > 12<<20 {
			return
		}
		b := make([]byte, n)
		if _, err := io.ReadFull(pipe, b); err != nil {
			return
		}
		if _, err := jpeg.DecodeConfig(bytes.NewReader(b)); err != nil {
			return
		}
		rc := http.NewResponseController(w)
		rc.SetWriteDeadline(time.Now().Add(5 * time.Second))
		if _, err := fmt.Fprintf(w, "--dexframe\r\nContent-Type: image/jpeg\r\nContent-Length: %d\r\n\r\n", n); err != nil {
			return
		}
		if _, err := w.Write(b); err != nil {
			return
		}
		if _, err := io.WriteString(w, "\r\n"); err != nil {
			return
		}
		if rc.Flush() != nil {
			return
		}
	}
}

type action struct {
	Kind, Key, Display string
	X, Y, EndX, EndY   float64
}

func input(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" || r.Header.Get("Origin") != "https://"+r.Host || r.Header.Get("X-DeX-Control") != "1" {
		http.Error(w, "Forbidden", 403)
		return
	}
	var a action
	if json.NewDecoder(http.MaxBytesReader(w, r.Body, 2048)).Decode(&a) != nil {
		http.Error(w, "Invalid input", 400)
		return
	}
	m, err := display(r.Context())
	if err != nil || m[1] != a.Display {
		http.Error(w, "Reconnect the desktop", 409)
		return
	}
	width, _ := strconv.Atoi(m[2])
	height, _ := strconv.Atoi(m[3])
	for _, v := range []float64{a.X, a.Y, a.EndX, a.EndY} {
		if v < 0 || v > 1 {
			http.Error(w, "Outside desktop", 400)
			return
		}
	}
	point := func(v float64, size int) string { return strconv.Itoa(int(v*float64(size-1) + 0.5)) }
	args := []string{"input", "-d", m[1]}
	switch a.Kind {
	case "tap":
		args = append(args, "tap", point(a.X, width), point(a.Y, height))
	case "drag":
		args = append(args, "swipe", point(a.X, width), point(a.Y, height), point(a.EndX, width), point(a.EndY, height), "300")
	case "key":
		if code, ok := keyCodes[a.Key]; ok {
			args = append(args, "keyevent", code)
		} else if len(a.Key) == 1 && a.Key[0] >= 33 && a.Key[0] <= 126 {
			args = append(args, "text", a.Key)
		} else {
			http.Error(w, "Unsupported key", 400)
			return
		}
	default:
		http.Error(w, "Unsupported action", 400)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	if exec.CommandContext(ctx, "/system/bin/cmd", args...).Run() != nil {
		http.Error(w, "Input failed", 503)
		return
	}
	w.WriteHeader(204)
}

func main() {
	if len(os.Args) != 2 {
		log.Fatal("expected Wi-Fi IPv4 address")
	}
	address := net.ParseIP(os.Args[1])
	if address == nil || address.To4() == nil || address.IsUnspecified() {
		log.Fatal("invalid address")
	}
	if err := prepareIdentity(address); err != nil {
		log.Fatal(err)
	}
	password, err := os.ReadFile("dex-password")
	if err != nil {
		log.Fatal(err)
	}
	secret := bytes.TrimSpace(password)
	if len(secret) < 24 {
		log.Fatal("invalid credential")
	}
	host := net.JoinHostPort(address.String(), "8443")
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		if r.Host != host {
			http.Error(w, "Unknown host", 400)
			return
		}
		user, pass, ok := r.BasicAuth()
		if !ok || user != "dex" || subtle.ConstantTimeCompare([]byte(pass), secret) != 1 {
			w.Header().Set("WWW-Authenticate", `Basic realm="Samsung DeX"`)
			http.Error(w, "Sign in", 401)
			return
		}
		switch r.URL.Path {
		case "/manage", "/manage/services", "/manage/share":
			manage(w, r)
		case "/":
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			w.Write(page)
		case "/screen":
			stream(w, r)
		case "/video":
			video(w, r)
		case "/display":
			m, err := display(r.Context())
			if err != nil {
				http.Error(w, err.Error(), 503)
				return
			}
			fmt.Fprint(w, m[1])
		case "/input":
			input(w, r)
		case "/control":
			inputSocket(w, r)
		default:
			http.NotFound(w, r)
		}
	})
	if err := supervise(host, handler); err != nil {
		log.Fatal(err)
	}
}
