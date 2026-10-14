# DroidDeck mark

`droiddeck-mark.svg` is the editable source for the white split-D mark. The blue orb is `#1A9FFF`; its halo and the two separator bars are transparent cutouts. The bars are centered on x=63.97, the endpoint of the curved half in the original Steam Deck symbol, and use the halo's 15.83-unit thickness. The mark is optically centered in its view box with a small rightward offset to balance its asymmetric silhouette. Place it on black when a background is needed.

Android currently consumes raster resources for the launcher layers and the Compose logo. The `mipmap-*/ic_launcher_foreground.png` files are density-sized exports of the SVG, with the mark kept inside the adaptive icon safe area. The matching backgrounds are black. `drawable-nodpi/logo.png` composes the same mark on a black circular badge.
