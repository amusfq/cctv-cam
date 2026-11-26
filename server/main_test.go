package main

import (
	"fmt"
	"net"
	"testing"

	rtsp "github.com/bluenviron/gortsplib/v5"
	"github.com/bluenviron/gortsplib/v5/pkg/base"
	"github.com/bluenviron/gortsplib/v5/pkg/description"
	"github.com/bluenviron/gortsplib/v5/pkg/format"
)

// Exercise the path and credentials exactly as an external viewer supplies them.
func TestViewerAccess(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	h := &camera{username: "test", password: "test-password", viewers: make(map[*rtsp.ServerSession]bool)}
	server := &rtsp.Server{Handler: h, RTSPAddress: listener.Addr().String(), Listen: func(string, string) (net.Listener, error) { return listener, nil }}
	if err := server.Start(); err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	stream := &rtsp.ServerStream{Server: server, Desc: &description.Session{Medias: []*description.Media{{Type: description.MediaTypeVideo, Formats: []format.Format{&format.H264{PayloadTyp: 96, PacketizationMode: 1}}}}}}
	if err := stream.Initialize(); err != nil {
		t.Fatal(err)
	}
	defer stream.Close()
	h.mu.Lock()
	h.stream = stream
	h.mu.Unlock()
	for _, tc := range []struct {
		password, path string
		status         base.StatusCode
	}{
		{"incorrect", "/live", base.StatusUnauthorized},
		{"test-password", "/other", base.StatusNotFound},
		{"test-password", "/live", base.StatusOK},
	} {
		t.Run(fmt.Sprintf("%s-%d", tc.path, tc.status), func(t *testing.T) {
			url, err := base.ParseURL("rtsp://test:" + tc.password + "@" + listener.Addr().String() + tc.path)
			if err != nil {
				t.Fatal(err)
			}
			client := &rtsp.Client{Scheme: url.Scheme, Host: url.Host}
			if err := client.Start(); err != nil {
				t.Fatal(err)
			}
			defer client.Close()
			_, res, err := client.Describe(url)
			if res == nil || res.StatusCode != tc.status {
				t.Fatalf("unexpected DESCRIBE result: %v, %v", res, err)
			}
		})
	}
}
