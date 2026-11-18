package main

import (
	"log"
	"net/http"
	"os"
	"time"
)

func main() {
	if len(os.Args) != 3 {
		log.Fatal("usage: web ADDRESS DOCUMENT_ROOT")
	}
	root, err := os.OpenRoot(os.Args[2])
	if err != nil {
		log.Fatal(err)
	}
	defer root.Close()
	files := http.FileServerFS(root.FS())
	server := &http.Server{
		Addr:              os.Args[1],
		ReadHeaderTimeout: 5 * time.Second,
		IdleTimeout:       30 * time.Second,
		Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.Method != http.MethodGet && r.Method != http.MethodHead {
				w.Header().Set("Allow", "GET, HEAD")
				http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
				return
			}
			files.ServeHTTP(w, r)
		}),
	}
	log.Fatal(server.ListenAndServe())
}
