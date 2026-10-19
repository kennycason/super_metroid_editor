;;; HUD Missile Watermark (Demo)
;;; Draws a permanent 3×2 missile icon in the normally empty
;;; upper-left corner of the HUD.

org Tilemap_HUD_topRow
    dw $344B,$3449,$744B

org Tilemap_HUD_rows123
    dw $344C,$344A,$744C
