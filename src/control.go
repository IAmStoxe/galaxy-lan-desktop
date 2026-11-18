package main

import (
	"bufio"
	"context"
	"errors"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strconv"
	"syscall"
	"time"
)

// Watch the service state directory for enable and disable requests.
func supervise(host string, handler http.Handler) error {
	tileDir := os.Getenv("DEX_STATE_DIR")
	if tileDir == "" {
		tileDir = "/data/local/tmp/galaxy-lan-desktop/browser-state"
	}
	marker := func(name string) string { return filepath.Join(tileDir, name) }
	fd, err := syscall.InotifyInit1(syscall.IN_CLOEXEC)
	if err != nil {
		return err
	}
	defer syscall.Close(fd)
	_, err = syscall.InotifyAddWatch(fd, tileDir, syscall.IN_CREATE|syscall.IN_DELETE|syscall.IN_MOVED_TO|syscall.IN_MOVED_FROM)
	if err != nil {
		return err
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()
	changes := make(chan struct{}, 1)
	go func() {
		var events [4096]byte
		for {
			if _, err := syscall.Read(fd, events[:]); err != nil {
				return
			}
			select {
			case changes <- struct{}{}:
			default:
			}
		}
	}()
	os.Remove(marker("listening"))
	if err = os.WriteFile(marker("ready"), nil, 0600); err != nil {
		return err
	}
	defer os.Remove(marker("ready"))
	defer os.Remove(marker("listening"))
	var srv *http.Server
	var endSession context.CancelFunc
	var monitor *exec.Cmd
	defer func() {
		if monitor != nil {
			monitor.Process.Kill()
			monitor.Wait()
		}
	}()
	defer func() {
		if srv != nil {
			srv.Close()
		}
	}()
	failures := make(chan error, 1)
	for {
		_, err = os.Stat(marker("enabled"))
		if err != nil && !errors.Is(err, os.ErrNotExist) {
			return err
		}
		if err == nil && srv == nil {
			monitor = exec.CommandContext(ctx, "/system/bin/app_process", "/", "HeadlessDex")
			monitor.Env = append(os.Environ(), "CLASSPATH=/data/local/tmp/galaxy-lan-desktop/HeadlessDex.jar")
			monitor.SysProcAttr = &syscall.SysProcAttr{Pdeathsig: syscall.SIGKILL}
			monitor.Stderr = os.Stderr
			stdout, err := monitor.StdoutPipe()
			if err != nil {
				return err
			}
			if err = monitor.Start(); err != nil {
				monitor = nil
				return err
			}
			scanner := bufio.NewScanner(stdout)
			if !scanner.Scan() {
				return errors.New("virtual display creation failed")
			}
			id, err := strconv.Atoi(scanner.Text())
			if err != nil || id <= 0 {
				return errors.New("invalid virtual display")
			}
			monitorID.Store(int32(id))
			ln, err := net.Listen("tcp4", host)
			if err != nil {
				return err
			}
			var session context.Context
			session, endSession = context.WithCancel(ctx)
			srv = &http.Server{Handler: handler, BaseContext: func(net.Listener) context.Context { return session }, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 10 * time.Second, IdleTimeout: 30 * time.Second}
			go func(s *http.Server) {
				if err := s.ServeTLS(ln, "dex-cert.pem", "dex-key.pem"); !errors.Is(err, http.ErrServerClosed) {
					failures <- err
				}
			}(srv)
			if err = os.WriteFile(marker("listening"), nil, 0600); err != nil {
				return err
			}
		} else if err != nil && srv != nil {
			endSession()
			srv.Close()
			srv = nil
			monitorID.Store(0)
			monitor.Process.Kill()
			monitor.Wait()
			monitor = nil
			os.Remove(marker("listening"))
		}
		select {
		case <-changes:
		case <-ctx.Done():
			return nil
		case err := <-failures:
			log.Print("HTTPS listener stopped")
			return err
		}
	}
}
