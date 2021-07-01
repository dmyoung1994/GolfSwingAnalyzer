# Golf Swing Analyzer
The main purpose of this app is to auto detect when a golf swing has started and ended in an effor to effortlessly record golf swings on a mobile device.
We accomplish this through the use of the [Google's MLKit pose detection library](https://developers.google.com/ml-kit/vision/pose-detection) to track different parts of the body and compare them to the previous frame.

## Implemented features
1. Auto Recording of a swing
2. Break down swing into different categories (Backsing, Downswing, Follow through) with time stamps
3. Back swing analysis numbers
4. Camera Overlay to show the swing on the screen

# TODOs
1. Improve performance of analyzer to get more frame's through the system
2. Add downswing and follow-through analysis
    1. I've compiled a list of metrics to calculate and track [here](https://docs.google.com/document/d/1-4sUph65BX2YA40cluUR53JrMSNNIu-OWOFTjscAOpU/edit?usp=sharing)
    2. All of these metrics can be calculated using simple trig and angle projection in order to get a "3D" value from a 2 dimensional set of points
3. Figure out how to increase the quality of the recorded video
    1. Currently it pieces together a video from the recorded frames used in analysis. These frames are heavily compressed for the sake of running through the ML algoritm and do not construct a high quality video. The frames are getting to the camera overlay in high quality. More investigation is needed.
