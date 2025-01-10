# Brighten-Up - AI-Powered Virtual Assistant for Visually Impaired

Overview:
This project is an Android-based application designed to empower visually impaired individuals by providing real-time assistance through cutting-edge technologies. It integrates multiple features into a single platform to enhance accessibility, independence, and ease of daily tasks.

Features:
1. Voice Interaction: A hands-free interface allowing users to control the app and navigate features using voice commands.
2. Text Recognition: Reads and converts printed or handwritten text into speech using the Google Cloud Vision API.
3. Object Detection: Identifies objects in the environment in real-time using TensorFlow Lite and provides voice feedback.
4. Face Recognition: Recognizes and announces known faces to help users identify familiar individuals.
5. Chatbot: Powered by GPT-3.5 API, assists users in navigating features and answering general queries.

Technologies Used:
1. Programming Languages: Java
2. Development Tools: Android Studio
3. APIs and Frameworks:
      Google Cloud Vision API for text recognition
      TensorFlow Lite for object detection
      OpenAI GPT-3.5 API for chatbot functionality
      Android SpeechRecognizer and TextToSpeech for voice interaction

How It Works:
Enable Microphone: Double tap on screen
Voice Commands: Users can activate modules (e.g., text recognition, object detection) through simple voice commands(e.g., Open Object Detection).
Real-Time Detection: The app processes the camera feed to identify text, objects, or faces and provides immediate audio feedback.
Chatbot Assistance: Users can interact with the chatbot for feature navigation or general queries.

Setup and Installation:
1. Clone this repository 
2. Open the project in Android Studio.
3. Add required API keys for Google Cloud Vision and OpenAI GPT-3.5 in the appropriate configuration files.
4. Build and run the app on an Android device.

