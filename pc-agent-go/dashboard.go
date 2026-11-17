package main

import (
	_ "embed"
	"encoding/json"
	"fmt"
	"html"
	"net/url"
	"strconv"
	"strings"
)

//go:embed dashboard.html
var dashboardHTMLTemplate string

func buildPairURI(lanIP string, port int, cfg HostConfig) string {
	return fmt.Sprintf(
		"deskdeck://pair?host=%s&port=%d&token=%s&name=%s",
		url.QueryEscape(lanIP),
		port,
		url.QueryEscape(cfg.Token),
		url.QueryEscape(cfg.PCName),
	)
}

func renderDashboardHTML(lanIP string, port int, cfg HostConfig) string {
	lang := normalizeLanguage(cfg.Language)
	cfg.Language = lang
	pairURI := buildPairURI(lanIP, port, cfg)
	qrSVG := encodeQRSVG(pairURI, 220)
	cfgBytes, _ := json.Marshal(cfg)
	rssMB := fmt.Sprintf("%.1f", readProcessRSSMBPlatform())

	replacer := strings.NewReplacer(
		"__LANG__", html.EscapeString(lang),
		"__PC_NAME__", html.EscapeString(cfg.PCName),
		"__RSS_MB__", rssMB,
		"__QR_SVG__", qrSVG,
		"__LAN_IP__", html.EscapeString(lanIP),
		"__PORT__", strconv.Itoa(port),
		"__TOKEN__", html.EscapeString(cfg.Token),
		"__CFG_JSON__", string(cfgBytes),
	)
	return replacer.Replace(dashboardHTMLTemplate)
}
