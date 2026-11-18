package main

import (
	"context"
	_ "embed"
	"encoding/json"
	"io"
	"io/fs"
	"mime"
	"net/http"
	"os"
	"os/exec"
	"path"
	"strings"
	"sync"
	"time"
)

//go:embed manage.html
var managementPage []byte
var serviceLock sync.Mutex

func manage(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && (r.Method != http.MethodPost || r.Header.Get("Origin") != "https://"+r.Host || r.Header.Get("X-DeX-Control") != "1") {
		http.Error(w, "Forbidden", 403)
		return
	}
	switch r.URL.Path {
	case "/manage":
		if r.Method != http.MethodGet {
			http.Error(w, "Method not allowed", 405)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write(managementPage)
	case "/manage/services":
		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
		defer cancel()
		serviceLock.Lock()
		defer serviceLock.Unlock()
		action := "status"
		if r.Method == http.MethodPost {
			action = r.URL.Query().Get("action")
			switch action {
			case "web-start", "web-stop", "smb-start", "smb-stop":
			default:
				http.Error(w, "Invalid service action", 400)
				return
			}
		}
		command := func(arg string) ([]byte, error) {
			return exec.CommandContext(ctx, "/system/bin/sh", "/data/local/tmp/galaxy-lan-desktop/lan-services.sh", arg).Output()
		}
		if action != "status" {
			if _, err := command(action); err != nil {
				http.Error(w, "Service action failed", 503)
				return
			}
		}
		out, err := command("status")
		if err != nil {
			http.Error(w, "Status unavailable", 503)
			return
		}
		status := map[string]bool{}
		for _, line := range strings.Split(string(out), "\n") {
			name, value, ok := strings.Cut(line, ": ")
			if ok {
				status[name] = value != "stopped"
			}
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(status)
	case "/manage/share":
		share(w, r)
	}
}

func share(w http.ResponseWriter, r *http.Request) {
	root, err := os.OpenRoot("/storage/emulated/0/GalaxyLANShare")
	if err != nil {
		http.Error(w, "Share storage unavailable", 503)
		return
	}
	defer root.Close()
	q := r.URL.Query()
	dir := q.Get("dir")
	if dir == "" {
		dir = "."
	}
	if !fs.ValidPath(dir) {
		http.Error(w, "Invalid folder", 400)
		return
	}
	name := q.Get("name")
	if name != "" && (!fs.ValidPath(name) || name == "." || strings.ContainsAny(name, "/\\\x00")) {
		http.Error(w, "Invalid file name", 400)
		return
	}
	target := path.Join(dir, name)
	if r.Method == http.MethodPost {
		if name == "" {
			http.Error(w, "Enter a name", 400)
			return
		}
		switch q.Get("action") {
		case "mkdir":
			if err := root.Mkdir(target, 0770); err != nil {
				http.Error(w, "Could not create folder", 409)
				return
			}
		case "upload":
			f, err := root.OpenFile(target, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0660)
			if err != nil {
				http.Error(w, "File exists or folder is unavailable", 409)
				return
			}
			_, err = io.Copy(f, http.MaxBytesReader(w, r.Body, 64<<20))
			closeErr := f.Close()
			if err != nil || closeErr != nil {
				root.Remove(target)
				http.Error(w, "Upload failed. Limit 64 MiB", 400)
				return
			}
		default:
			http.Error(w, "Invalid action", 400)
			return
		}
		w.WriteHeader(204)
		return
	}
	if name != "" {
		f, err := root.Open(target)
		if err != nil {
			http.NotFound(w, r)
			return
		}
		defer f.Close()
		info, err := f.Stat()
		if err != nil || !info.Mode().IsRegular() {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Disposition", mime.FormatMediaType("attachment", map[string]string{"filename": name}))
		w.Header().Set("Content-Type", "application/octet-stream")
		http.ServeContent(w, r, name, info.ModTime(), f)
		return
	}
	entries, err := fs.ReadDir(root.FS(), dir)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	type entry struct {
		Name   string
		Folder bool
	}
	result := make([]entry, 0, len(entries))
	for _, e := range entries {
		if e.IsDir() || e.Type().IsRegular() {
			result = append(result, entry{e.Name(), e.IsDir()})
		}
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(result)
}
