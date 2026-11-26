// The camera sends encoded access units through stdin. Only the RTSP reader is network-accessible.
package main

import (
	"bufio"
	"encoding/binary"
	"fmt"
	"io"
	"log"
	"os"
	"sync"
	"time"

	rtsp "github.com/bluenviron/gortsplib/v5"
	"github.com/bluenviron/gortsplib/v5/pkg/base"
	"github.com/bluenviron/gortsplib/v5/pkg/description"
	"github.com/bluenviron/gortsplib/v5/pkg/format"
	"github.com/bluenviron/gortsplib/v5/pkg/liberrors"
	"github.com/bluenviron/mediacommon/v2/pkg/codecs/h264"
	"github.com/pion/rtp"
)

type camera struct {
	mu       sync.Mutex
	stream   *rtsp.ServerStream
	viewers  map[*rtsp.ServerSession]bool
	username string
	password string
}

func (c *camera) read(conn *rtsp.ServerConn, req *base.Request, path string) (*base.Response, *rtsp.ServerStream, error) {
	if !conn.VerifyCredentials(req, c.username, c.password) {
		return &base.Response{StatusCode: base.StatusUnauthorized}, nil, liberrors.ErrServerAuth{}
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if path != "/live" || c.stream == nil {
		return &base.Response{StatusCode: base.StatusNotFound}, nil, nil
	}
	return &base.Response{StatusCode: base.StatusOK}, c.stream, nil
}
func (c *camera) OnDescribe(x *rtsp.ServerHandlerOnDescribeCtx) (*base.Response, *rtsp.ServerStream, error) {
	return c.read(x.Conn, x.Request, x.Path)
}
func (c *camera) OnSetup(x *rtsp.ServerHandlerOnSetupCtx) (*base.Response, *rtsp.ServerStream, error) {
	return c.read(x.Conn, x.Request, x.Path)
}
func (c *camera) OnPlay(x *rtsp.ServerHandlerOnPlayCtx) (*base.Response, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.viewers[x.Session] = true
	return &base.Response{StatusCode: base.StatusOK}, nil
}
func (c *camera) OnSessionClose(x *rtsp.ServerHandlerOnSessionCloseCtx) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.viewers, x.Session)
}
func (c *camera) OnPause(x *rtsp.ServerHandlerOnPauseCtx) (*base.Response, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.viewers, x.Session)
	return &base.Response{StatusCode: base.StatusOK}, nil
}

func run() error {
	c := &camera{viewers: make(map[*rtsp.ServerSession]bool), username: os.Getenv("CAMERA_USER"), password: os.Getenv("CAMERA_PASSWORD")}
	if c.username == "" || c.password == "" {
		return fmt.Errorf("missing credential")
	}
	video := &format.H264{PayloadTyp: 96, PacketizationMode: 1}
	audio := &format.LPCM{PayloadTyp: 97, SampleRate: 16000, BitDepth: 16, ChannelCount: 1}
	vEnc, err := video.CreateEncoder()
	if err != nil {
		return err
	}
	aEnc, err := audio.CreateEncoder()
	if err != nil {
		return err
	}
	v := &description.Media{Type: description.MediaTypeVideo, Formats: []format.Format{video}}
	a := &description.Media{Type: description.MediaTypeAudio, Formats: []format.Format{audio}}
	desc := &description.Session{Medias: []*description.Media{v}}
	if os.Getenv("CAMERA_MIC") == "1" {
		desc.Medias = append(desc.Medias, a)
	}
	server := &rtsp.Server{Handler: c, RTSPAddress: ":8554"}
	if err = server.Start(); err != nil {
		return err
	}
	defer server.Close()
	stream := &rtsp.ServerStream{Server: server, Desc: desc}
	if err = stream.Initialize(); err != nil {
		return err
	}
	defer stream.Close()
	in := bufio.NewReader(os.Stdin)
	var sps, pps []byte
	last := time.Now()
	frames := 0
	var lastBytes uint64
	for {
		var header [13]byte
		if _, err = io.ReadFull(in, header[:]); err != nil {
			return err
		}
		pts := binary.BigEndian.Uint64(header[1:9])
		size := binary.BigEndian.Uint32(header[9:])
		if size == 0 || size > 8<<20 {
			return fmt.Errorf("invalid frame size")
		}
		data := make([]byte, size)
		if _, err = io.ReadFull(in, data); err != nil {
			return err
		}
		var packets []*rtp.Packet
		media := v
		clock := uint64(90000)
		switch header[0] {
		case 0, 1:
			var au h264.AnnexB
			if err = au.Unmarshal(data); err != nil {
				return err
			}
			paramsChanged := false
			for _, nal := range au {
				switch nal[0] & 31 {
				case 7:
					sps = nal
					paramsChanged = true
				case 8:
					pps = nal
					paramsChanged = true
				}
			}
			if paramsChanged && len(sps) > 0 && len(pps) > 0 {
				video.SafeSetParams(sps, pps)
				c.mu.Lock()
				c.stream = stream
				c.mu.Unlock()
			}
			if header[0] == 0 {
				continue
			}
			packets, err = vEnc.Encode(au)
			frames++
		case 2:
			media = a
			clock = 16000
			packets, err = aEnc.Encode(data)
		default:
			return fmt.Errorf("unknown frame type")
		}
		if err != nil {
			return err
		}
		for _, p := range packets {
			p.Timestamp += uint32(pts * clock / 1000000)
			if err = stream.WritePacketRTP(media, p); err != nil {
				return err
			}
		}
		if elapsed := time.Since(last).Seconds(); elapsed >= 2 {
			total := stream.Stats().BytesSent
			c.mu.Lock()
			count := len(c.viewers)
			c.mu.Unlock()
			fmt.Printf("stats %.1f %.0f %d\n", float64(frames)/elapsed, float64(total-lastBytes)*8/elapsed/1000, count)
			last = time.Now()
			lastBytes = total
			frames = 0
		}
	}
}
func main() {
	if err := run(); err != nil && err != io.EOF {
		log.Fatal(err)
	}
}
