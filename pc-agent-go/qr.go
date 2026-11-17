package main

import (
	"fmt"
	"strings"
)

type qrVersion struct {
	ver   int
	total int
	ec    int
	b1    int
	d1    int
	b2    int
	d2    int
	align []int
}

var qrVersions = []qrVersion{
	{1, 26, 7, 1, 19, 0, 0, nil},
	{2, 44, 10, 1, 34, 0, 0, []int{6, 18}},
	{3, 70, 15, 1, 55, 0, 0, []int{6, 22}},
	{4, 100, 20, 1, 80, 0, 0, []int{6, 26}},
	{5, 134, 26, 1, 108, 0, 0, []int{6, 30}},
	{6, 172, 18, 2, 68, 0, 0, []int{6, 34}},
	{7, 196, 20, 2, 78, 0, 0, []int{6, 22, 38}},
	{8, 242, 24, 2, 97, 0, 0, []int{6, 24, 42}},
}

var (
	gfExp [512]int
	gfLog [256]int
)

func init() {
	x := 1
	for i := 0; i < 255; i++ {
		gfExp[i] = x
		gfLog[x] = i
		x <<= 1
		if x&0x100 != 0 {
			x ^= 0x11D
		}
	}
	for i := 255; i < 512; i++ {
		gfExp[i] = gfExp[i-255]
	}
}

func gfMul(a, b int) int {
	if a == 0 || b == 0 {
		return 0
	}
	return gfExp[gfLog[a]+gfLog[b]]
}

func rsGenPoly(degree int) []int {
	poly := []int{1}
	for i := 0; i < degree; i++ {
		nxt := make([]int, len(poly)+1)
		g := gfExp[i]
		for j, c := range poly {
			nxt[j] ^= c
			nxt[j+1] ^= gfMul(c, g)
		}
		poly = nxt
	}
	return poly
}

func rsRemainder(data []int, ecLen int) []int {
	gen := rsGenPoly(ecLen)
	res := make([]int, len(data)+ecLen)
	copy(res, data)
	for i := 0; i < len(data); i++ {
		coef := res[i]
		if coef != 0 {
			for j := 1; j < len(gen); j++ {
				res[i+j] ^= gfMul(gen[j], coef)
			}
		}
	}
	return res[len(data):]
}

func encodeQRMatrix(text string) [][]int {
	raw := []byte(text)
	chosen := qrVersions[len(qrVersions)-1]
	found := false
	for _, v := range qrVersions {
		capBytes := v.b1*v.d1 + v.b2*v.d2
		if len(raw)+2 <= capBytes {
			chosen = v
			found = true
			break
		}
	}
	if !found && len(raw) > 190 {
		raw = raw[:190]
	}

	dataCap := chosen.b1*chosen.d1 + chosen.b2*chosen.d2
	size := 17 + 4*chosen.ver

	bits := make([]int, 0, dataCap*8)
	pushBits := func(val, length int) {
		for i := length - 1; i >= 0; i-- {
			bits = append(bits, (val>>i)&1)
		}
	}

	pushBits(0b0100, 4) // Byte mode
	pushBits(len(raw), 8)
	for _, b := range raw {
		pushBits(int(b), 8)
	}
	termLen := dataCap*8 - len(bits)
	if termLen > 4 {
		termLen = 4
	}
	if termLen > 0 {
		pushBits(0, termLen)
	}
	for len(bits)%8 != 0 {
		bits = append(bits, 0)
	}
	padBytes := []int{0xEC, 0x11}
	pIdx := 0
	for len(bits) < dataCap*8 {
		pushBits(padBytes[pIdx%2], 8)
		pIdx++
	}

	codewords := make([]int, len(bits)/8)
	for i := 0; i < len(codewords); i++ {
		cw := 0
		for j := 0; j < 8; j++ {
			cw += bits[i*8+j] << (7 - j)
		}
		codewords[i] = cw
	}

	var blocks [][]int
	var ecBlocks [][]int
	offset := 0
	groups := [][2]int{{chosen.b1, chosen.d1}, {chosen.b2, chosen.d2}}
	for _, grp := range groups {
		for c := 0; c < grp[0]; c++ {
			blk := make([]int, grp[1])
			copy(blk, codewords[offset:offset+grp[1]])
			offset += grp[1]
			blocks = append(blocks, blk)
			ecBlocks = append(ecBlocks, rsRemainder(blk, chosen.ec))
		}
	}

	maxD := 0
	for _, b := range blocks {
		if len(b) > maxD {
			maxD = len(b)
		}
	}

	interleaved := make([]int, 0, chosen.total)
	for i := 0; i < maxD; i++ {
		for _, b := range blocks {
			if i < len(b) {
				interleaved = append(interleaved, b[i])
			}
		}
	}
	for i := 0; i < chosen.ec; i++ {
		for _, eb := range ecBlocks {
			interleaved = append(interleaved, eb[i])
		}
	}

	finalBits := make([]int, 0, len(interleaved)*8+7)
	for _, cw := range interleaved {
		for i := 7; i >= 0; i-- {
			finalBits = append(finalBits, (cw>>i)&1)
		}
	}
	for i := 0; i < 7; i++ {
		finalBits = append(finalBits, 0)
	}

	grid := make([][]int, size)
	isFunc := make([][]bool, size)
	for i := 0; i < size; i++ {
		grid[i] = make([]int, size)
		isFunc[i] = make([]bool, size)
	}

	setFunc := func(r, c, val int) {
		if r >= 0 && r < size && c >= 0 && c < size {
			grid[r][c] = val
			isFunc[r][c] = true
		}
	}

	drawFinder := func(r0, c0 int) {
		for dr := -1; dr <= 7; dr++ {
			for dc := -1; dc <= 7; dc++ {
				r, c := r0+dr, c0+dc
				if r >= 0 && r < size && c >= 0 && c < size {
					inBox := dr >= 0 && dr <= 6 && dc >= 0 && dc <= 6
					border := dr == 0 || dr == 6 || dc == 0 || dc == 6
					center := dr >= 2 && dr <= 4 && dc >= 2 && dc <= 4
					if inBox && (border || center) {
						setFunc(r, c, 1)
					} else {
						setFunc(r, c, 0)
					}
				}
			}
		}
	}

	drawFinder(0, 0)
	drawFinder(0, size-7)
	drawFinder(size-7, 0)

	for i := 8; i < size-8; i++ {
		val := 0
		if i%2 == 0 {
			val = 1
		}
		setFunc(6, i, val)
		setFunc(i, 6, val)
	}

	for _, ar := range chosen.align {
		for _, ac := range chosen.align {
			if isFunc[ar][ac] {
				continue
			}
			for dr := -2; dr <= 2; dr++ {
				for dc := -2; dc <= 2; dc++ {
					val := 0
					if dr == -2 || dr == 2 || dc == -2 || dc == 2 || (dr == 0 && dc == 0) {
						val = 1
					}
					setFunc(ar+dr, ac+dc, val)
				}
			}
		}
	}

	setFunc(size-8, 8, 1)

	for i := 0; i < 9; i++ {
		if !isFunc[8][i] {
			isFunc[8][i] = true
			grid[8][i] = 0
		}
		if !isFunc[i][8] {
			isFunc[i][8] = true
			grid[i][8] = 0
		}
	}
	for i := size - 8; i < size; i++ {
		isFunc[8][i] = true
		grid[8][i] = 0
	}
	for i := size - 7; i < size; i++ {
		isFunc[i][8] = true
		grid[i][8] = 0
	}

	bitIdx := 0
	col := size - 1
	up := true
	for col > 0 {
		if col == 6 {
			col--
		}
		for step := 0; step < size; step++ {
			r := step
			if up {
				r = size - 1 - step
			}
			for _, c := range []int{col, col - 1} {
				if !isFunc[r][c] {
					b := 0
					if bitIdx < len(finalBits) {
						b = finalBits[bitIdx]
					}
					bitIdx++
					if (r+c)%2 == 0 {
						b ^= 1
					}
					grid[r][c] = b
				}
			}
		}
		col -= 2
		up = !up
	}

	// Format info for ECC Level L (01) + Mask 0 (000) -> BCH = 0x77C4
	fmtBits := 0x77C4
	for i := 0; i < 15; i++ {
		bit := (fmtBits >> (14 - i)) & 1
		if i < 6 {
			grid[8][i] = bit
		} else if i < 8 {
			grid[8][i+1] = bit
		} else if i == 8 {
			grid[7][8] = bit
		} else {
			grid[14-i][8] = bit
		}

		if i < 7 {
			grid[size-1-i][8] = bit
		} else if i == 7 {
			grid[8][size-8] = bit
		} else {
			grid[8][size-15+i] = bit
		}
	}

	return grid
}

func encodeQRSVG(text string, boxSize int) string {
	grid := encodeQRMatrix(text)
	size := len(grid)
	quiet := 3
	full := size + quiet*2
	var sb strings.Builder
	fmt.Fprintf(&sb, `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" width="%d" height="%d" shape-rendering="crispEdges">`, full, full, boxSize, boxSize)
	fmt.Fprintf(&sb, `<rect width="%d" height="%d" fill="#FFFFFF" rx="2"/><g fill="#0B0E14">`, full, full)
	for r := 0; r < size; r++ {
		for c := 0; c < size; c++ {
			if grid[r][c] == 1 {
				fmt.Fprintf(&sb, `<rect x="%d" y="%d" width="1" height="1"/>`, c+quiet, r+quiet)
			}
		}
	}
	sb.WriteString(`</g></svg>`)
	return sb.String()
}

func renderQRTerminal(text string) string {
	grid := encodeQRMatrix(text)
	size := len(grid)
	quiet := 2
	full := size + quiet*2
	padded := make([][]int, full)
	for r := 0; r < full; r++ {
		padded[r] = make([]int, full)
	}
	for r := 0; r < size; r++ {
		for c := 0; c < size; c++ {
			padded[r+quiet][c+quiet] = grid[r][c]
		}
	}

	var lines []string
	for r := 0; r < full; r += 2 {
		var row strings.Builder
		row.WriteString("  ")
		for c := 0; c < full; c++ {
			topWhite := padded[r][c] == 0
			botWhite := true
			if r+1 < full {
				botWhite = padded[r+1][c] == 0
			}
			switch {
			case topWhite && botWhite:
				row.WriteRune('█')
			case topWhite && !botWhite:
				row.WriteRune('▀')
			case !topWhite && botWhite:
				row.WriteRune('▄')
			default:
				row.WriteRune(' ')
			}
		}
		lines = append(lines, row.String())
	}
	return strings.Join(lines, "\n")
}
