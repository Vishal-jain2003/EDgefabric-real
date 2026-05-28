export default {
  content: ['./index.html', './src/**/*.{js,jsx}'],
  theme: {
    extend: {
      animation: {
        'fade-in-up': 'fadeInUp 0.5s ease-out forwards',
        'fade-in': 'fadeIn 0.3s ease-out forwards',
        'slide-in-right': 'slideInRight 0.3s ease-out forwards',
        'slide-in-left': 'slideInLeft 0.3s ease-out forwards',
        'pulse-dot': 'pulseDot 2s ease-in-out infinite',
        'glow-pulse': 'glowPulse 2.5s ease-in-out infinite',
        'bar-grow': 'barGrow 1s ease-out forwards',
        shimmer: 'shimmer 1.8s infinite',
        'spin-slow': 'spin 4s linear infinite',
        'float': 'float 3s ease-in-out infinite',
      },
      keyframes: {
        fadeInUp: {
          '0%': { opacity: '0', transform: 'translateY(20px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        fadeIn: {
          '0%': { opacity: '0' },
          '100%': { opacity: '1' },
        },
        slideInRight: {
          '0%': { opacity: '0', transform: 'translateX(24px)' },
          '100%': { opacity: '1', transform: 'translateX(0)' },
        },
        slideInLeft: {
          '0%': { opacity: '0', transform: 'translateX(-24px)' },
          '100%': { opacity: '1', transform: 'translateX(0)' },
        },
        pulseDot: {
          '0%, 100%': { transform: 'scale(1)', opacity: '1' },
          '50%': { transform: 'scale(1.5)', opacity: '0.5' },
        },
        glowPulse: {
          '0%, 100%': { boxShadow: '0 0 8px rgba(220,38,38,0.4), 0 0 20px rgba(220,38,38,0.1)' },
          '50%': { boxShadow: '0 0 20px rgba(220,38,38,0.7), 0 0 50px rgba(220,38,38,0.25)' },
        },
        barGrow: {
          '0%': { width: '0%' },
          '100%': { width: 'var(--target-width, 0%)' },
        },
        shimmer: {
          '0%': { backgroundPosition: '-200% 0' },
          '100%': { backgroundPosition: '200% 0' },
        },
        float: {
          '0%, 100%': { transform: 'translateY(0px)' },
          '50%': { transform: 'translateY(-4px)' },
        },
      },
    },
  },
  plugins: [],
}
