/* NeonGridBackground — 深度优化版
 * 黑色网格背景 + 光波从底部爬升（红→紫→青）。
 * 优化：网格缓存、分层绘制、渐变步数压缩、跳过远处线条
 */
(function () {
  function hexToRgb(hex) {
    var h = hex.replace('#', '')
    return {
      r: parseInt(h.slice(0, 2), 16),
      g: parseInt(h.slice(2, 4), 16),
      b: parseInt(h.slice(4, 6), 16),
    }
  }

  function lerp(a, b, t) {
    return a + (b - a) * t
  }

  function gradientAt(colors, u) {
    var c = colors
    if (u < 0.25) return c[0]
    if (u < 0.38) {
      var t = (u - 0.25) / 0.13
      return { r: lerp(c[0].r, c[1].r, t), g: lerp(c[0].g, c[1].g, t), b: lerp(c[0].b, c[1].b, t) }
    }
    if (u < 0.62) return c[1]
    if (u < 0.75) {
      var t2 = (u - 0.62) / 0.13
      return { r: lerp(c[1].r, c[2].r, t2), g: lerp(c[1].g, c[2].g, t2), b: lerp(c[1].b, c[2].b, t2) }
    }
    return c[2]
  }

  function cssColor(c, alpha) {
    return 'rgba(' + (c.r | 0) + ',' + (c.g | 0) + ',' + (c.b | 0) + ',' + alpha + ')'
  }

  function NeonGridBackground(opts) {
    opts = opts || {}
    var gridSize = opts.gridSize !== undefined ? opts.gridSize : 36
    var waveDuration = opts.waveDuration !== undefined ? opts.waveDuration : 10000
    var bandHeight = opts.bandHeight !== undefined ? opts.bandHeight : 0.32
    var falloff = opts.falloff !== undefined ? opts.falloff : 0.85
    var baseLineAlpha = opts.baseLineAlpha !== undefined ? opts.baseLineAlpha : 0.03
    var glowAlpha = opts.glowAlpha !== undefined ? opts.glowAlpha : 0.9
    var backgroundColor = opts.backgroundColor !== undefined ? opts.backgroundColor : '#000000'
    var haloAlpha = opts.haloAlpha !== undefined ? opts.haloAlpha : 0.2
    var renderScale = opts.renderScale !== undefined ? opts.renderScale : 0.45

    var rawColors = opts.colors || ['#ff2d55', '#a855f7', '#22d3ee']
    var colors = rawColors.map(hexToRgb)

    var canvas = opts.canvas || document.getElementById('neonCanvas')
    if (!canvas) return null
    var ctx = canvas.getContext('2d')
    if (!ctx) return null

    var raf = 0
    var width = 0
    var height = 0
    var dpr = Math.min(window.devicePixelRatio || 1, 2)
    var params = new URLSearchParams(window.location.search)
    var waveParam = Number(params.get('wave'))
    var paused = params.get('pause') === '1'
    var phase = Number.isFinite(waveParam) ? ((waveParam % 1) + 1) % 1 : 0
    var start = performance.now() - phase * waveDuration

    var off = null
    var octx = null
    var gridCache = null
    var gridCacheW = 0
    var gridCacheH = 0

    var resize = function () {
      width = window.innerWidth
      height = window.innerHeight
      canvas.width = Math.round(width * dpr)
      canvas.height = Math.round(height * dpr)
      canvas.style.width = width + 'px'
      canvas.style.height = height + 'px'
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
      ctx.imageSmoothingEnabled = true
      ctx.imageSmoothingQuality = 'low'
      off = document.createElement('canvas')
      off.width = Math.max(1, Math.round(width * renderScale))
      off.height = Math.max(1, Math.round(height * renderScale))
      octx = off.getContext('2d')
      if (octx) {
        octx.scale(off.width / width, off.height / height)
        octx.lineCap = 'round'
      }
      gridCache = null
    }
    resize()
    window.addEventListener('resize', resize)

    function buildGridCache() {
      if (gridCache && gridCacheW === width && gridCacheH === height) return
      var gc = document.createElement('canvas')
      gc.width = Math.round(width * renderScale)
      gc.height = Math.round(height * renderScale)
      var gctx = gc.getContext('2d')
      if (!gctx) return
      gctx.scale(gc.width / width, gc.height / height)
      gctx.strokeStyle = 'rgba(255,255,255,' + baseLineAlpha + ')'
      gctx.lineWidth = 1
      gctx.beginPath()
      for (var x = 0; x <= width; x += gridSize) {
        gctx.moveTo(x + 0.5, 0)
        gctx.lineTo(x + 0.5, height)
      }
      for (var y = 0; y <= height; y += gridSize) {
        gctx.moveTo(0, y + 0.5)
        gctx.lineTo(width, y + 0.5)
      }
      gctx.stroke()
      gridCache = gc
      gridCacheW = width
      gridCacheH = height
    }

    var render = function (now) {
      ctx.fillStyle = backgroundColor
      ctx.fillRect(0, 0, width, height)
      if (!off || !octx) {
        raf = requestAnimationFrame(render)
        return
      }

      var t = paused ? phase : ((now - start) % waveDuration) / waveDuration
      var waveY = height * (1.35 - 1.75 * t)
      var radius = Math.max(height * bandHeight * 0.5, 40)
      var afterglow = radius * 1.6
      var bandTop = waveY - radius * 0.6
      var bandBottom = waveY + afterglow
      var invHeight = 1 / height

      var colorAt = function (yy) { return gradientAt(colors, 1 - Math.min(Math.max(yy * invHeight, 0), 1)) }

      var intensity = function (yy) {
        var d = yy - waveY
        return d > 0
          ? Math.exp(-(d * d / (afterglow * afterglow)) * 1.15)
          : Math.exp(-(d * d / (radius * radius)) * falloff)
      }

      octx.fillStyle = backgroundColor
      octx.fillRect(0, 0, width, height)

      buildGridCache()
      if (gridCache) {
        octx.drawImage(gridCache, 0, 0, width, height)
      }

      var visibleTop = Math.max(bandTop, 0)
      var visibleBottom = Math.min(bandBottom, height)
      var vertGlow = Math.min(1, (visibleBottom - visibleTop) / (bandBottom - bandTop))
      if (vertGlow > 0.03) {
        var grad = octx.createLinearGradient(0, visibleTop, 0, visibleBottom)
        var vsteps = 6
        var vHeight = visibleBottom - visibleTop
        for (var i = 0; i <= vsteps; i++) {
          var yy = visibleTop + (vHeight * i) / vsteps
          grad.addColorStop(i / vsteps, cssColor(colorAt(yy), 1))
        }
        octx.globalAlpha = vertGlow * glowAlpha * 0.3
        octx.strokeStyle = grad
        octx.lineWidth = 3
        octx.beginPath()
        for (var vi2 = 0; vi2 <= width; vi2 += gridSize) {
          octx.moveTo(vi2 + 0.5, visibleTop)
          octx.lineTo(vi2 + 0.5, visibleBottom)
        }
        octx.stroke()
        octx.globalAlpha = vertGlow * glowAlpha * 0.55
        octx.lineWidth = 1.2
        octx.beginPath()
        for (var vi3 = 0; vi3 <= width; vi3 += gridSize) {
          octx.moveTo(vi3 + 0.5, visibleTop)
          octx.lineTo(vi3 + 0.5, visibleBottom)
        }
        octx.stroke()
        octx.globalAlpha = 1
      }

      var yStart = Math.max(0, Math.floor((bandTop - afterglow) / gridSize) * gridSize)
      var yEnd = Math.min(height, bandBottom + afterglow)
      for (var yy2 = yStart; yy2 <= yEnd; yy2 += gridSize) {
        var inten = intensity(yy2)
        if (inten <= 0.015) continue
        var cc = colorAt(yy2)
        var a = inten * glowAlpha
        if (inten > 0.3) {
          octx.strokeStyle = cssColor(cc, a * 0.32)
          octx.lineWidth = 4
          octx.beginPath()
          octx.moveTo(0, yy2 + 0.5)
          octx.lineTo(width, yy2 + 0.5)
          octx.stroke()
          octx.strokeStyle = cssColor(cc, Math.min(a, 1))
          octx.lineWidth = 1
          octx.beginPath()
          octx.moveTo(0, yy2 + 0.5)
          octx.lineTo(width, yy2 + 0.5)
          octx.stroke()
        } else {
          octx.strokeStyle = cssColor(cc, a * 0.5)
          octx.lineWidth = 2
          octx.beginPath()
          octx.moveTo(0, yy2 + 0.5)
          octx.lineTo(width, yy2 + 0.5)
          octx.stroke()
        }
      }

      if (haloAlpha > 0 && bandBottom > 0 && bandTop < height) {
        var pad = radius * 0.35
        var hTop = Math.max(0, bandTop - pad)
        var hBottom = Math.min(height, bandBottom + pad)
        var grad2 = octx.createLinearGradient(0, hTop, 0, hBottom)
        var hsteps = 6
        var hHeight = hBottom - hTop
        for (var i2 = 0; i2 <= hsteps; i2++) {
          var t2 = i2 / hsteps
          var yy3 = hTop + hHeight * t2
          var envelope = Math.exp(-Math.pow((yy3 - waveY) / (radius * 1.05), 2) * 1.2)
          grad2.addColorStop(t2, cssColor(colorAt(yy3), haloAlpha * envelope))
        }
        octx.fillStyle = grad2
        octx.fillRect(0, hTop, width, hHeight)
      }

      ctx.drawImage(off, 0, 0, width, height)
      raf = requestAnimationFrame(render)
    }
    raf = requestAnimationFrame(render)

    return {
      destroy: function () {
        cancelAnimationFrame(raf)
        window.removeEventListener('resize', resize)
      },
    }
  }

  window.NeonGridBackground = NeonGridBackground
})()
