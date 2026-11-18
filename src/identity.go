package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/pem"
	"errors"
	"math/big"
	"net"
	"os"
	"time"
)

func prepareIdentity(ip net.IP) error {
	if _, err := os.Stat("dex-password"); errors.Is(err, os.ErrNotExist) {
		var b [30]byte
		if _, err = rand.Read(b[:]); err != nil {
			return err
		}
		if err = os.WriteFile("dex-password", []byte(base64.RawURLEncoding.EncodeToString(b[:])+"\n"), 0600); err != nil {
			return err
		}
	} else if err != nil {
		return err
	}
	if cert, err := tls.LoadX509KeyPair("dex-cert.pem", "dex-key.pem"); err == nil {
		leaf, err := x509.ParseCertificate(cert.Certificate[0])
		if err == nil && leaf.VerifyHostname(ip.String()) == nil && time.Until(leaf.NotAfter) > 24*time.Hour {
			return nil
		}
	}
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return err
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return err
	}
	cert := x509.Certificate{SerialNumber: serial, Subject: pkix.Name{CommonName: "Galaxy LAN Desktop"}, IPAddresses: []net.IP{ip}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().AddDate(1, 0, 0), KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	der, err := x509.CreateCertificate(rand.Reader, &cert, &cert, &key.PublicKey, key)
	if err != nil {
		return err
	}
	raw, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return err
	}
	if err = os.WriteFile("dex-key.pem", pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: raw}), 0600); err != nil {
		return err
	}
	return os.WriteFile("dex-cert.pem", pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0600)
}
